package com.marcuschiu.meet.util;

import org.webrtc.Logging;
import org.webrtc.VideoRenderer;

public class ProxyVideoRendererCallbacks implements VideoRenderer.Callbacks {
    private VideoRenderer.Callbacks target;

    @Override
    synchronized public void renderFrame(VideoRenderer.I420Frame frame) {
        if (target == null) {
            Logging.d("ProxyVideoRendererCallbacks", "Dropping frame in proxy because target is null.");
            VideoRenderer.renderFrameDone(frame);
            return;
        }

        target.renderFrame(frame);
    }

    synchronized public void setTarget(VideoRenderer.Callbacks target) {
        this.target = target;
    }
}
