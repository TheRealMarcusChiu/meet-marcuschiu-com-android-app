package com.marcuschiu.meet.client.pc;

import android.content.Context;
import android.util.Log;

import com.marcuschiu.meet.client.AppRTCClient;
import com.marcuschiu.meet.client.util.Util;

import org.webrtc.AudioSource;
import org.webrtc.AudioTrack;
import org.webrtc.CameraVideoCapturer;
import org.webrtc.DataChannel;
import org.webrtc.DefaultVideoDecoderFactory;
import org.webrtc.DefaultVideoEncoderFactory;
import org.webrtc.EglBase;
import org.webrtc.IceCandidate;
import org.webrtc.MediaConstraints;
import org.webrtc.MediaStream;
import org.webrtc.PeerConnection;
import org.webrtc.PeerConnection.IceConnectionState;
import org.webrtc.PeerConnectionFactory;
import org.webrtc.RtpParameters;
import org.webrtc.RtpReceiver;
import org.webrtc.RtpSender;
import org.webrtc.SdpObserver;
import org.webrtc.SessionDescription;
import org.webrtc.VideoCapturer;
import org.webrtc.VideoRenderer;
import org.webrtc.VideoSink;
import org.webrtc.VideoSource;
import org.webrtc.VideoTrack;
import org.webrtc.voiceengine.WebRtcAudioManager;
import org.webrtc.voiceengine.WebRtcAudioRecord;
import org.webrtc.voiceengine.WebRtcAudioRecord.AudioRecordStartErrorCode;
import org.webrtc.voiceengine.WebRtcAudioRecord.WebRtcAudioRecordErrorCallback;
import org.webrtc.voiceengine.WebRtcAudioTrack;
import org.webrtc.voiceengine.WebRtcAudioTrack.AudioTrackStartErrorCode;
import org.webrtc.voiceengine.WebRtcAudioUtils;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Iterator;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class PeerConnectionClient {

    private static final String TAG = "PCRTCClient";

    public static final String VIDEO_TRACK_ID = "ARDAMSv0";
    public static final String AUDIO_TRACK_ID = "ARDAMSa0";
    public static final String VIDEO_TRACK_TYPE = "video";
    public static final String VIDEO_CODEC_VP8 = "VP8";
    public static final String AUDIO_CODEC_OPUS = "opus";
    private static final String VIDEO_VP8_INTEL_HW_ENCODER_FIELDTRIAL = "WebRTC-IntelVP8/Enabled/";
    private static final String VIDEO_FRAME_EMIT_FIELDTRIAL = PeerConnectionFactory.VIDEO_FRAME_EMIT_TRIAL + "/" + PeerConnectionFactory.TRIAL_ENABLED + "/";
    private static final String AUDIO_LEVEL_CONTROL_CONSTRAINT = "levelControl";
    private static final int HD_VIDEO_WIDTH = 1280;
    private static final int HD_VIDEO_HEIGHT = 720;
    private static final int FRAMES_PER_SECOND = 720;
    private static final int BPS_IN_KBPS = 1000;

    // Executor thread is started once in private ctor and is used for all
    // peer connection API calls to ensure new peer connection factory is
    // created on the same thread as previously destroyed factory.
    private static final ExecutorService executor = Executors.newSingleThreadExecutor();

    private final SDPObserver sdpObserver = new SDPObserver();

    private final EglBase rootEglBase;
    private PeerConnectionFactory pcFactory = null;
    private PeerConnection pc = null;

    private PeerConnectionEvents events;
    private boolean isError = false;
    private boolean isInitiator;

    private VideoRenderer.Callbacks remoteVideo;
    private MediaConstraints sdpMediaConstraints;
    // Queued remote ICE candidates are consumed only after both local and
    // remote descriptions are set. Similarly local ICE candidates are sent to
    // remote peer after both local and remote description are set.
    private List<IceCandidate> queuedRemoteCandidates = null;
    private SessionDescription localSdp = null; // either offer or answer SDP

    private boolean videoCapturerStopped = false;
    private VideoCapturer videoCapturer = null;
    private VideoTrack remoteVideoTrack;
    private RtpSender localVideoSender;
    private AudioSource audioSource;
    private VideoSource videoSource;

    public PeerConnectionClient() {
        rootEglBase = EglBase.create();
    }

    public void createPeerConnectionFactory(final Context context, final PeerConnectionEvents events) {
        this.events = events;

        executor.execute(() -> {
            PeerConnectionFactory.InitializationOptions options = PeerConnectionFactory.InitializationOptions.builder(context)
                    .setFieldTrials(VIDEO_VP8_INTEL_HW_ENCODER_FIELDTRIAL + VIDEO_FRAME_EMIT_FIELDTRIAL)
                    .createInitializationOptions();
            PeerConnectionFactory.initialize(options);

            WebRtcAudioManager.setBlacklistDeviceForOpenSLESUsage(true);
            WebRtcAudioUtils.setWebRtcBasedAcousticEchoCanceler(true);
            WebRtcAudioUtils.setWebRtcBasedAutomaticGainControl(true);
            WebRtcAudioUtils.setWebRtcBasedNoiseSuppressor(true);

            WebRtcAudioRecord.setErrorCallback(new WebRtcAudioRecordErrorCallback() {
                @Override
                public void onWebRtcAudioRecordInitError(String errorMessage) {
                    Log.e(TAG, "onWebRtcAudioRecordInitError: " + errorMessage);
                    reportError(errorMessage);
                }

                @Override
                public void onWebRtcAudioRecordStartError(AudioRecordStartErrorCode errorCode, String errorMessage) {
                    Log.e(TAG, "onWebRtcAudioRecordStartError: " + errorCode + ". " + errorMessage);
                    reportError(errorMessage);
                }

                @Override
                public void onWebRtcAudioRecordError(String errorMessage) {
                    Log.e(TAG, "onWebRtcAudioRecordError: " + errorMessage);
                    reportError(errorMessage);
                }
            });
            WebRtcAudioTrack.setErrorCallback(new WebRtcAudioTrack.ErrorCallback() {
                @Override
                public void onWebRtcAudioTrackInitError(String errorMessage) {
                    Log.e(TAG, "onWebRtcAudioTrackInitError: " + errorMessage);
                    reportError(errorMessage);
                }

                @Override
                public void onWebRtcAudioTrackStartError(AudioTrackStartErrorCode errorCode, String errorMessage) {
                    Log.e(TAG, "onWebRtcAudioTrackStartError: " + errorCode + ". " + errorMessage);
                    reportError(errorMessage);
                }

                @Override
                public void onWebRtcAudioTrackError(String errorMessage) {
                    Log.e(TAG, "onWebRtcAudioTrackError: " + errorMessage);
                    reportError(errorMessage);
                }
            });

            pcFactory = new PeerConnectionFactory(null,
                    new DefaultVideoEncoderFactory(rootEglBase.getEglBaseContext(), true, false),
                    new DefaultVideoDecoderFactory(rootEglBase.getEglBaseContext()));
//            PeerConnectionFactory.builder()
//                    .setVideoEncoderFactory(new DefaultVideoEncoderFactory(rootEglBase.getEglBaseContext(), true, false))
//                    .setVideoDecoderFactory(new DefaultVideoDecoderFactory(rootEglBase.getEglBaseContext()))
//                    .createPeerConnectionFactory();
        });
    }

    public void createPeerConnection(final VideoSink localVideo, final VideoRenderer.Callbacks remoteVideo, final VideoCapturer videoCapturer, final AppRTCClient.SignalingParameters signalingParameters) {
        this.remoteVideo = remoteVideo;
        this.videoCapturer = videoCapturer;
        executor.execute(() -> {
            try {
                ///////////////////////////
                // CREATE SDP CONSTRAINT //
                ///////////////////////////
                sdpMediaConstraints = new MediaConstraints();
                sdpMediaConstraints.mandatory.add(new MediaConstraints.KeyValuePair("OfferToReceiveAudio", "true"));
                sdpMediaConstraints.mandatory.add(new MediaConstraints.KeyValuePair("OfferToReceiveVideo", "true"));

                ////////////////////////////
                // CREATE PEER CONNECTION //
                ////////////////////////////
                queuedRemoteCandidates = new ArrayList<>();

                pcFactory.setVideoHwAccelerationOptions(rootEglBase.getEglBaseContext(), rootEglBase.getEglBaseContext());

                PeerConnection.RTCConfiguration rtcConfig = new PeerConnection.RTCConfiguration(signalingParameters.iceServers);
                rtcConfig.tcpCandidatePolicy = PeerConnection.TcpCandidatePolicy.DISABLED; // TCP candidates are only useful when connecting to a server that supports ICE-TCP
                rtcConfig.bundlePolicy = PeerConnection.BundlePolicy.MAXBUNDLE;
                rtcConfig.rtcpMuxPolicy = PeerConnection.RtcpMuxPolicy.REQUIRE;
                rtcConfig.continualGatheringPolicy = PeerConnection.ContinualGatheringPolicy.GATHER_CONTINUALLY;
                rtcConfig.keyType = PeerConnection.KeyType.ECDSA; // Use ECDSA encryption
                rtcConfig.enableDtlsSrtp = true; // Enable DTLS for normal calls and disable for loopback calls

                pc = pcFactory.createPeerConnection(rtcConfig, new PCObserver());

                MediaStream mediaStream = pcFactory.createLocalMediaStream("ARDAMS");

                videoSource = pcFactory.createVideoSource(videoCapturer);
                videoCapturer.startCapture(HD_VIDEO_WIDTH, HD_VIDEO_HEIGHT, FRAMES_PER_SECOND);
                VideoTrack localVideoTrack = pcFactory.createVideoTrack(VIDEO_TRACK_ID, videoSource);
                localVideoTrack.setEnabled(true);
                localVideoTrack.addSink(localVideo);
                mediaStream.addTrack(localVideoTrack);

                MediaConstraints audioConstraints = new MediaConstraints();
                audioConstraints.mandatory.add(new MediaConstraints.KeyValuePair(AUDIO_LEVEL_CONTROL_CONSTRAINT, "true"));
                audioSource = pcFactory.createAudioSource(audioConstraints);
                AudioTrack localAudioTrack = pcFactory.createAudioTrack(AUDIO_TRACK_ID, audioSource);
                localAudioTrack.setEnabled(true);
                mediaStream.addTrack(localAudioTrack);

                pc.addStream(mediaStream);
                for (RtpSender sender : pc.getSenders()) {
                    if (sender.track() != null) {
                        String trackType = sender.track().kind();
                        if (trackType.equals(VIDEO_TRACK_TYPE)) {
                            localVideoSender = sender;
                        }
                    }
                }
            } catch (Exception e) {
                reportError("Failed to create peer connection: " + e.getMessage());
                throw e;
            }
        });
    }

    public void close() {
        executor.execute(() -> {
            if (pc != null) {
                pc.dispose();
                pc = null;
            }
            if (audioSource != null) {
                audioSource.dispose();
                audioSource = null;
            }
            if (videoCapturer != null) {
                try {
                    videoCapturer.stopCapture();
                } catch (InterruptedException e) {
                    throw new RuntimeException(e);
                }
                videoCapturerStopped = true;
                videoCapturer.dispose();
                videoCapturer = null;
            }
            if (videoSource != null) {
                videoSource.dispose();
                videoSource = null;
            }
            remoteVideo = null;
            if (pcFactory != null) {
                pcFactory.dispose();
                pcFactory = null;
            }
            rootEglBase.release();
            events.onPeerConnectionClosed();
            PeerConnectionFactory.stopInternalTracingCapture();
            PeerConnectionFactory.shutdownInternalTracer();
            events = null;
        });
    }

    public EglBase.Context getRenderContext() {
        return rootEglBase.getEglBaseContext();
    }

    public void createOffer() {
        executor.execute(() -> {
            if (pc != null && !isError) {
                isInitiator = true;
                pc.createOffer(sdpObserver, sdpMediaConstraints);
            }
        });
    }

    public void createAnswer() {
        executor.execute(() -> {
            if (pc != null && !isError) {
                isInitiator = false;
                pc.createAnswer(sdpObserver, sdpMediaConstraints);
            }
        });
    }

    public void addRemoteIceCandidate(final IceCandidate candidate) {
        executor.execute(() -> {
            if (pc != null && !isError) {
                if (queuedRemoteCandidates != null) {
                    queuedRemoteCandidates.add(candidate);
                } else {
                    pc.addIceCandidate(candidate);
                }
            }
        });
    }

    public void removeRemoteIceCandidates(final IceCandidate[] candidates) {
        executor.execute(() -> {
            if (pc == null || isError) {
                return;
            }
            // Drain the queued remote candidates if there is any so that they are processed in the proper order.
            drainCandidates();
            pc.removeIceCandidates(candidates);
        });
    }

    public void setRemoteDescription(final SessionDescription sdp) {
        executor.execute(() -> {
            String sdpDescription = sdp.description;
            sdpDescription = Util.preferCodec(sdpDescription);
            sdpDescription = Util.setStartBitrate(sdpDescription);
            pc.setRemoteDescription(sdpObserver, new SessionDescription(sdp.type, sdpDescription));
        });
    }

    public void stopVideoSource() {
        executor.execute(() -> {
            if (videoCapturer != null && !videoCapturerStopped) {
                try {
                    videoCapturer.stopCapture();
                } catch (InterruptedException e) {
                }
                videoCapturerStopped = true;
            }
        });
    }

    public void startVideoSource() {
        executor.execute(() -> {
            if (videoCapturer != null && videoCapturerStopped) {
                videoCapturer.startCapture(HD_VIDEO_WIDTH, HD_VIDEO_HEIGHT, FRAMES_PER_SECOND);
                videoCapturerStopped = false;
            }
        });
    }

    public void setVideoMaxBitrate(final Integer maxBitrateKbps) {
        executor.execute(() -> {
            if (pc == null || localVideoSender == null || isError) {
                return;
            }

            RtpParameters parameters = localVideoSender.getParameters();
            if (parameters.encodings.size() == 0) {
                Log.w(TAG, "RtpParameters are not ready.");
                return;
            }

            for (RtpParameters.Encoding encoding : parameters.encodings) {
                // Null value means no limit.
                encoding.maxBitrateBps = maxBitrateKbps == null ? null : maxBitrateKbps * BPS_IN_KBPS;
            }
            if (!localVideoSender.setParameters(parameters)) {
                Log.e(TAG, "RtpSender.setParameters failed.");
            }
        });
    }

    private void reportError(final String errorMessage) {
        Log.e(TAG, "Peerconnection error: " + errorMessage);
        executor.execute(() -> {
            if (!isError) {
                events.onPeerConnectionError(errorMessage);
                isError = true;
            }
        });
    }

    private void drainCandidates() {
        if (queuedRemoteCandidates != null) {
            for (IceCandidate candidate : queuedRemoteCandidates) {
                pc.addIceCandidate(candidate);
            }
            queuedRemoteCandidates = null;
        }
    }

    public void switchCamera() {
        executor.execute(() -> {
            if (videoCapturer instanceof CameraVideoCapturer) {
                CameraVideoCapturer cameraVideoCapturer = (CameraVideoCapturer) videoCapturer;
                cameraVideoCapturer.switchCamera(null);
            } else {
                Log.d(TAG, "Will not switch camera, video caputurer is not a camera");
            }
        });
    }


    private class PCObserver implements PeerConnection.Observer {
        @Override
        public void onIceCandidate(final IceCandidate candidate) {
            executor.execute(() -> events.onIceCandidate(candidate));
        }

        @Override
        public void onIceCandidatesRemoved(final IceCandidate[] candidates) {
            executor.execute(() -> events.onIceCandidatesRemoved(candidates));
        }

        @Override
        public void onSignalingChange(PeerConnection.SignalingState newState) {
        }

        @Override
        public void onIceConnectionChange(final IceConnectionState newState) {
            executor.execute(() -> {
                if (newState == IceConnectionState.CONNECTED) {
                    events.onIceConnected();
                } else if (newState == IceConnectionState.DISCONNECTED) {
                    events.onIceDisconnected();
                } else if (newState == IceConnectionState.FAILED) {
                    reportError("ICE connection failed.");
                }
            });
        }

        @Override
        public void onIceGatheringChange(PeerConnection.IceGatheringState newState) {
        }

        @Override
        public void onIceConnectionReceivingChange(boolean receiving) {
        }

        @Override
        public void onAddStream(final MediaStream stream) {
            executor.execute(() -> {
                if (pc == null || isError) {
                    return;
                }
                if (stream.audioTracks.size() > 1 || stream.videoTracks.size() > 1) {
                    reportError("Weird-looking stream: " + stream);
                    return;
                }
                if (stream.videoTracks.size() == 1) {
                    remoteVideoTrack = stream.videoTracks.get(0);
                    remoteVideoTrack.setEnabled(true);
                    remoteVideoTrack.addRenderer(new VideoRenderer(remoteVideo));
                }
            });
        }

        @Override
        public void onRemoveStream(final MediaStream stream) {
            executor.execute(() -> remoteVideoTrack = null);
        }

        @Override
        public void onDataChannel(final DataChannel dc) {
        }

        @Override
        public void onRenegotiationNeeded() {
            // No need to do anything; AppRTC follows a pre-agreed-upon
            // signaling/negotiation protocol.
        }

        @Override
        public void onAddTrack(final RtpReceiver receiver, final MediaStream[] mediaStreams) {
        }
    }

    private class SDPObserver implements SdpObserver {
        @Override
        public void onCreateSuccess(final SessionDescription origSdp) {
            localSdp = new SessionDescription(origSdp.type, Util.preferCodec(origSdp.description));
            executor.execute(() -> pc.setLocalDescription(sdpObserver, localSdp));
        }

        @Override
        public void onSetSuccess() {
            executor.execute(() -> {
                if (isInitiator) {
                    // For offering peer connection we first create offer and set local SDP, then after receiving answer set remote SDP.
                    if (pc.getRemoteDescription() == null) {
                        // We've just set our local SDP so time to send it.
                        events.onLocalDescription(localSdp);
                    } else {
                        // We've just set remote description, so drain remote and send local ICE candidates.
                        drainCandidates();
                    }
                } else {
                    // For answering peer connection we set remote SDP and then create answer and set local SDP.
                    if (pc.getLocalDescription() != null) {
                        // We've just set our local SDP so time to send it, drain remote and send local ICE candidates.
                        events.onLocalDescription(localSdp);
                        drainCandidates();
                    } else {
                        // We've just set remote SDP - do nothing for now - answer will be created soon.
                    }
                }
            });
        }

        @Override
        public void onCreateFailure(final String error) {
            reportError("createSDP error: " + error);
        }

        @Override
        public void onSetFailure(final String error) {
            reportError("setSDP error: " + error);
        }
    }
}
