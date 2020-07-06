package com.marcuschiu.meet.util;

import android.content.Context;

import org.webrtc.Camera2Enumerator;
import org.webrtc.VideoCapturer;

public class CameraUtil {

    public static VideoCapturer getVideoCapturer(Context context) {
        Camera2Enumerator enumerator = new Camera2Enumerator(context);
        final String[] deviceNames = enumerator.getDeviceNames();

        // First, try to find front facing camera
        for (String deviceName : deviceNames) {
            if (enumerator.isFrontFacing(deviceName)) {
                VideoCapturer videoCapturer = enumerator.createCapturer(deviceName, null);
                if (videoCapturer != null) {
                    return videoCapturer;
                }
            }
        }

        // Front facing camera not found, try something else
        for (String deviceName : deviceNames) {
            VideoCapturer videoCapturer = enumerator.createCapturer(deviceName, null);
            if (videoCapturer != null) {
                return videoCapturer;
            }
        }

        return null;
    }
}
