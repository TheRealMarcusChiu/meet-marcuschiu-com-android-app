package com.marcuschiu.meet.client.util;

import org.json.JSONException;
import org.json.JSONObject;

public class Util {
    public static void jsonPut(JSONObject json, String key, Object value) {
        try {
            json.put(key, value);
        } catch (JSONException e) {
            throw new RuntimeException(e);
        }
    }
}
