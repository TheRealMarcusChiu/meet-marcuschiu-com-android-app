package com.marcuschiu.meet.client;

import android.os.Handler;
import android.os.HandlerThread;
import android.util.Log;

import com.marcuschiu.meet.client.util.AsyncHttpURLConnection;
import com.marcuschiu.meet.client.util.Util;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import org.webrtc.IceCandidate;
import org.webrtc.SessionDescription;

public class WebSocketRTCClient implements AppRTCClient, WebSocketChannelClient.WebSocketChannelEvents {

    private enum ConnectionState {NEW, CONNECTED, CLOSED, ERROR}

    private enum MessageType {MESSAGE, LEAVE}

    private final Handler handler;
    private boolean initiator;
    private SignalingEvents events;
    private WebSocketChannelClient wsClient;
    private ConnectionState roomState;

    private String messageUrl;
    private String leaveUrl;

    public WebSocketRTCClient(SignalingEvents events) {
        this.events = events;
        final HandlerThread handlerThread = new HandlerThread("WSRTCClient");
        handlerThread.start();
        this.handler = new Handler(handlerThread.getLooper());
        this.wsClient = new WebSocketChannelClient(handler, this);
    }

    @Override
    public void connectToRoom(String roomID) {
        handler.post(() -> {
            roomState = ConnectionState.NEW;

            RoomParametersFetcher.RoomParametersFetcherEvents callbacks = new RoomParametersFetcher.RoomParametersFetcherEvents() {
                @Override
                public void onSignalingParametersReady(final SignalingParameters params) {
                    WebSocketRTCClient.this.handler.post(() -> {
                        roomState = ConnectionState.CONNECTED;

                        initiator = params.initiator;
                        messageUrl = "https://appr.tc/message/" + roomID + "/" + params.clientId;
                        leaveUrl = "https://appr.tc/leave/" + roomID + "/" + params.clientId;

                        events.onConnectedToRoom(params);

                        wsClient.connect(params.wssUrl, params.wssPostUrl);
                        wsClient.register(roomID, params.clientId);
                    });
                }

                @Override
                public void onSignalingParametersError(String description) {
                    WebSocketRTCClient.this.reportError(description);
                }
            };

            new RoomParametersFetcher("https://appr.tc/join/" + roomID, null, callbacks).makeRequest();
        });
    }

    @Override
    public void disconnectFromRoom() {
        handler.post(() -> {
            disconnectFromRoomInternal();
            handler.getLooper().quit();
        });
    }

    private void disconnectFromRoomInternal() {
        if (roomState == ConnectionState.CONNECTED) {
            sendPostMessage(MessageType.LEAVE, leaveUrl, null);
        }
        roomState = ConnectionState.CLOSED;
        if (wsClient != null) {
            wsClient.disconnect(true);
        }
    }

    @Override
    public void sendOfferSdp(final SessionDescription sdp) {
        handler.post(() -> {
            JSONObject json = new JSONObject();
            Util.jsonPut(json, "sdp", sdp.description);
            Util.jsonPut(json, "type", "offer");
            sendPostMessage(MessageType.MESSAGE, messageUrl, json.toString());
        });
    }

    @Override
    public void sendAnswerSdp(final SessionDescription sdp) {
        handler.post(() -> {
            JSONObject json = new JSONObject();
            Util.jsonPut(json, "sdp", sdp.description);
            Util.jsonPut(json, "type", "answer");
            wsClient.send(json.toString());
        });
    }

    @Override
    public void sendLocalIceCandidate(final IceCandidate candidate) {
        handler.post(() -> {
            JSONObject json = new JSONObject();
            Util.jsonPut(json, "type", "candidate");
            Util.jsonPut(json, "label", candidate.sdpMLineIndex);
            Util.jsonPut(json, "id", candidate.sdpMid);
            Util.jsonPut(json, "candidate", candidate.sdp);
            if (initiator) {
                // Call initiator sends ice candidates to GAE server.
                if (roomState != ConnectionState.CONNECTED) {
                    reportError("Sending ICE candidate in non connected state.");
                    return;
                }
                sendPostMessage(MessageType.MESSAGE, messageUrl, json.toString());
            } else {
                // Call receiver sends ice candidates to websocket server.
                wsClient.send(json.toString());
            }
        });
    }

    @Override
    public void sendLocalIceCandidateRemovals(final IceCandidate[] candidates) {
        handler.post(() -> {
            JSONObject json = new JSONObject();
            Util.jsonPut(json, "type", "remove-candidates");
            JSONArray jsonArray = new JSONArray();
            for (final IceCandidate candidate : candidates) {
                jsonArray.put(Util.toJsonCandidate(candidate));
            }
            Util.jsonPut(json, "candidates", jsonArray);
            if (initiator) {
                // Call initiator sends ice candidates to GAE server.
                if (roomState != ConnectionState.CONNECTED) {
                    reportError("Sending ICE candidate removals in non connected state.");
                    return;
                }
                sendPostMessage(MessageType.MESSAGE, messageUrl, json.toString());
            } else {
                // Call receiver sends ice candidates to websocket server.
                wsClient.send(json.toString());
            }
        });
    }


    private void reportError(final String errorMessage) {
        Log.e("WSRTCClient", errorMessage);
        handler.post(() -> {
            if (roomState != ConnectionState.ERROR) {
                roomState = ConnectionState.ERROR;
                events.onChannelError(errorMessage);
            }
        });
    }

    // Send SDP or ICE candidate to a room server.
    private void sendPostMessage(final MessageType messageType, final String url, final String message) {
        AsyncHttpURLConnection httpConnection = new AsyncHttpURLConnection("POST", url, message, new AsyncHttpURLConnection.AsyncHttpEvents() {
                    @Override
                    public void onHttpError(String errorMessage) {
                        reportError("GAE POST error: " + errorMessage);
                    }

                    @Override
                    public void onHttpComplete(String response) {
                        if (messageType == MessageType.MESSAGE) {
                            try {
                                JSONObject roomJson = new JSONObject(response);
                                String result = roomJson.getString("result");
                                if (!result.equals("SUCCESS")) {
                                    reportError("GAE POST error: " + result);
                                }
                            } catch (JSONException e) {
                                reportError("GAE POST JSON error: " + e.toString());
                            }
                        }
                    }
                });
        httpConnection.send();
    }


    ////////////////////////////
    // WebSocketChannelEvents //
    ////////////////////////////

    @Override
    public void onWebSocketMessage(final String msg) {
        try {
            JSONObject json = new JSONObject(msg).getJSONObject("msg");
            String type = json.optString("type");

            switch (type) {
                case "candidate":
                    events.onRemoteIceCandidate(Util.toJavaCandidate(json));
                    break;
                case "remove-candidates":
                    JSONArray candidateArray = json.getJSONArray("candidates");
                    IceCandidate[] candidates = new IceCandidate[candidateArray.length()];
                    for (int i = 0; i < candidateArray.length(); ++i) {
                        candidates[i] = Util.toJavaCandidate(candidateArray.getJSONObject(i));
                    }
                    events.onRemoteIceCandidatesRemoved(candidates);
                    break;
                case "answer":
                case "offer":
                    events.onRemoteDescription(new SessionDescription(SessionDescription.Type.fromCanonicalForm(type), json.getString("sdp")));
                    break;
                case "bye":
                    events.onChannelClose();
                    break;
                default:
                    reportError("Unexpected WebSocket message: " + msg);
            }
        } catch (JSONException e) {
            reportError("WebSocket message JSON parsing error: " + e.toString());
        }
    }

    @Override
    public void onWebSocketClose() {
        events.onChannelClose();
    }

    @Override
    public void onWebSocketError(String description) {
        reportError("WebSocket error: " + description);
    }
}
