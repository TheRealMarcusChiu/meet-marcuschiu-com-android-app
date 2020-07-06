package com.marcuschiu.meet.android;

import android.app.Activity;
import android.app.AlertDialog;
import android.os.Bundle;
import android.util.Log;
import android.widget.ImageButton;
import android.widget.TextView;

import com.marcuschiu.meet.client.AppRTCClient;
import com.marcuschiu.meet.client.pc.PeerConnectionClient;
import com.marcuschiu.meet.client.pc.PeerConnectionEvents;
import com.marcuschiu.meet.client.WebSocketRTCClient;
import com.marcuschiu.meet.util.CameraUtil;
import com.marcuschiu.meet.util.ProxyVideoRendererCallbacks;
import com.marcuschiu.meet.util.ProxyVideoSink;

import org.webrtc.IceCandidate;
import org.webrtc.RendererCommon;
import org.webrtc.SessionDescription;
import org.webrtc.StatsReport;
import org.webrtc.SurfaceViewRenderer;
import org.webrtc.VideoCapturer;
import org.webrtc.VideoRenderer;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

public class CallActivity extends Activity implements AppRTCClient.SignalingEvents, PeerConnectionEvents {

    ProxyVideoRendererCallbacks remoteVideo = new ProxyVideoRendererCallbacks();
    ProxyVideoSink localVideo = new ProxyVideoSink();
    List<VideoRenderer.Callbacks> remoteVideos = new ArrayList<>();

    PeerConnectionClient pcClient = new PeerConnectionClient();
    AppRTCClient appRtcClient;

    boolean isInitiator = false;
    boolean activityRunning;
    boolean iceConnected = false;
    boolean isError = false;
    boolean isSwappedFeeds = true;

    SurfaceViewRenderer svrSmall;
    SurfaceViewRenderer svrFull;

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_call);

        // UI Controls
        ImageButton disconnectButton = findViewById(R.id.button_call_disconnect);
        disconnectButton.setOnClickListener(v -> disconnect());

        ImageButton cameraSwitchButton = findViewById(R.id.button_call_switch_camera);
        cameraSwitchButton.setOnClickListener(view -> pcClient.switchCamera());

        // Video Setup
        remoteVideos.add(remoteVideo);

        svrSmall = findViewById(R.id.pip_video_view);
        svrSmall.setOnClickListener(view -> setSwappedFeeds(!isSwappedFeeds)); // Swap feeds on pip view click.
        svrSmall.init(pcClient.getRenderContext(), null);
        svrSmall.setScalingType(RendererCommon.ScalingType.SCALE_ASPECT_FIT);
        svrSmall.setZOrderMediaOverlay(true);
        svrSmall.setEnableHardwareScaler(true);

        svrFull = findViewById(R.id.fullscreen_video_view);
        svrFull.init(pcClient.getRenderContext(), null);
        svrFull.setScalingType(RendererCommon.ScalingType.SCALE_ASPECT_FILL);
        svrFull.setEnableHardwareScaler(true);

        setSwappedFeeds(true); // Start with local feed in fullscreen and swap it when call is connected

        pcClient.createPeerConnectionFactory(getApplicationContext(), this);

        String roomID = "JESUS-" + new Random().nextInt(100);
        ((TextView) findViewById(R.id.roomID)).setText("ROOM ID: " + roomID);

        // connect to room
        appRtcClient = new WebSocketRTCClient(this);
        appRtcClient.connectToRoom(new AppRTCClient.RoomConnectionParameters(
                "https://appr.tc",
                roomID,
                false));
    }

    private void setSwappedFeeds(boolean isSwappedFeeds) {
        this.isSwappedFeeds = isSwappedFeeds;
        localVideo.setTarget(isSwappedFeeds ? svrFull : svrSmall);
        remoteVideo.setTarget(isSwappedFeeds ? svrSmall : svrFull);
        svrFull.setMirror(isSwappedFeeds);
        svrSmall.setMirror(!isSwappedFeeds);
    }


    //////////////////////////////////////////////////////////
    // Implementation of AppRTCClient.AppRTCSignalingEvents //
    //////////////////////////////////////////////////////////

    @Override
    public void onConnectedToRoom(final AppRTCClient.SignalingParameters sp) {
        runOnUiThread(() -> {
            isInitiator = sp.initiator;

            VideoCapturer videoCapturer = CameraUtil.getVideoCapturer(this);
            if (videoCapturer == null) {
                reportError("Failed to open camera");
            }
            pcClient.createPeerConnection(localVideo, remoteVideos, videoCapturer, sp);

            if (sp.initiator) {
                pcClient.createOffer(); // creates PeerConnectionEvents.onLocalDescription event
            } else {
                if (sp.offerSdp != null) {
                    pcClient.setRemoteDescription(sp.offerSdp);
                    pcClient.createAnswer(); // creates PeerConnectionEvents.onLocalDescription event
                }
                if (sp.iceCandidates != null) {
                    for (IceCandidate ic : sp.iceCandidates) {
                        pcClient.addRemoteIceCandidate(ic);
                    }
                }
            }
        });
    }

    @Override
    public void onRemoteDescription(final SessionDescription sdp) {
        pcClient.setRemoteDescription(sdp);
        if (!isInitiator) {
            pcClient.createAnswer(); // PeerConnectionEvents.onLocalDescription event
        }
    }

    @Override
    public void onRemoteIceCandidate(final IceCandidate candidate) {
        pcClient.addRemoteIceCandidate(candidate);
    }

    @Override
    public void onRemoteIceCandidatesRemoved(final IceCandidate[] candidates) {
        pcClient.removeRemoteIceCandidates(candidates);
    }

    @Override
    public void onChannelClose() {
        disconnect();
    }

    @Override
    public void onChannelError(final String description) {
        reportError(description);
    }


    /////////////////////////////////////////////////////////////////
    // Implementation of PeerConnectionClient.PeerConnectionEvents //
    /////////////////////////////////////////////////////////////////

    // Send local peer connection SDP and ICE candidates to remote party.
    // All callbacks are invoked from peer connection client looper thread

    @Override
    public void onLocalDescription(final SessionDescription sdp) {
        if (appRtcClient != null) {
            if (sdp.type == SessionDescription.Type.OFFER) {
                appRtcClient.sendOfferSdp(sdp);
            } else {
                appRtcClient.sendAnswerSdp(sdp);
            }
        }
        pcClient.setVideoMaxBitrate(1700);
    }

    @Override
    public void onIceCandidate(final IceCandidate candidate) {
        appRtcClient.sendLocalIceCandidate(candidate);
    }

    @Override
    public void onIceCandidatesRemoved(final IceCandidate[] candidates) {
        appRtcClient.sendLocalIceCandidateRemovals(candidates);
    }

    @Override
    public void onIceConnected() {
        iceConnected = true;
        setSwappedFeeds(false);
    }

    @Override
    public void onIceDisconnected() {
        iceConnected = false;
        disconnect();
    }

    @Override
    public void onPeerConnectionClosed() {
    }

    @Override
    public void onPeerConnectionStatsReady(final StatsReport[] reports) {
    }

    @Override
    public void onPeerConnectionError(final String description) {
        reportError(description);
    }


    /////////////////////////
    // Activity Interfaces //
    /////////////////////////

    @Override
    public void onStart() {
        super.onStart();
        activityRunning = true;
        pcClient.startVideoSource(); // Video is not paused for screencapture. See onPause.
    }

    @Override
    public void onStop() {
        super.onStop();
        activityRunning = false;
        pcClient.stopVideoSource();
    }

    @Override
    protected void onDestroy() {
        Thread.setDefaultUncaughtExceptionHandler(null);
        disconnect();
        activityRunning = false;
        super.onDestroy();
    }


    //////////////////////
    // Helper Functions //
    //////////////////////

    private void disconnect() {
        activityRunning = false;
        remoteVideo.setTarget(null);
        localVideo.setTarget(null);
        if (appRtcClient != null) {
            appRtcClient.disconnectFromRoom();
            appRtcClient = null;
        }
        if (svrSmall != null) {
            svrSmall.release();
            svrSmall = null;
        }
        if (svrFull != null) {
            svrFull.release();
            svrFull = null;
        }
        if (pcClient != null) {
            pcClient.close();
            pcClient = null;
        }
        if (iceConnected && !isError) {
            setResult(RESULT_OK);
        } else {
            setResult(RESULT_CANCELED);
        }
        finish();
    }

    private void disconnectWithErrorMessage(final String errorMessage) {
        if (!activityRunning) {
            Log.e("CallActivity", "Critical error: " + errorMessage);
            disconnect();
        } else {
            new AlertDialog.Builder(this)
                    .setTitle("Connection Error")
                    .setMessage(errorMessage)
                    .setCancelable(false)
                    .setNeutralButton("OK",
                            (dialog, id) -> {
                                dialog.cancel();
                                disconnect();
                            })
                    .create()
                    .show();
        }
    }

    private void reportError(final String description) {
        runOnUiThread(() -> {
            if (!isError) {
                isError = true;
                disconnectWithErrorMessage(description);
            }
        });
    }
}