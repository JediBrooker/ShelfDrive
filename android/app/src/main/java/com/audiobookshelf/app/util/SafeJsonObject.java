package com.audiobookshelf.app.util;

import androidx.annotation.Nullable;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import org.json.JSONException;
import org.json.JSONObject;

/**
 * Small no-throw JSON object used by the native AAOS networking layer.
 *
 * The old implementation used Capacitor's JSObject solely for these helpers.
 * Keeping the behavior here avoids shipping a WebView/plugin bridge in the
 * dedicated Automotive artifact.
 */
public final class SafeJsonObject extends JSONObject {
    public SafeJsonObject() {
        super();
    }

    public SafeJsonObject(String json) throws JSONException {
        super(json);
    }

    private SafeJsonObject(JSONObject object, String[] names) throws JSONException {
        super(object, names);
    }

    @Override
    @Nullable
    public String getString(String key) {
        try {
            if (!isNull(key)) {
                return super.getString(key);
            }
        } catch (JSONException ignored) {
            // Missing or malformed values are represented as null to callers.
        }
        return null;
    }

    @Nullable
    public SafeJsonObject getJSObject(String key) {
        JSONObject object = optJSONObject(key);
        if (object == null) return null;
        try {
            Iterator<String> iterator = object.keys();
            List<String> names = new ArrayList<>();
            while (iterator.hasNext()) names.add(iterator.next());
            return new SafeJsonObject(object, names.toArray(new String[0]));
        } catch (JSONException ignored) {
            return null;
        }
    }

    @Override
    public SafeJsonObject put(String key, boolean value) {
        try {
            super.put(key, value);
        } catch (JSONException ignored) {}
        return this;
    }

    @Override
    public SafeJsonObject put(String key, int value) {
        try {
            super.put(key, value);
        } catch (JSONException ignored) {}
        return this;
    }

    @Override
    public SafeJsonObject put(String key, long value) {
        try {
            super.put(key, value);
        } catch (JSONException ignored) {}
        return this;
    }

    @Override
    public SafeJsonObject put(String key, double value) {
        try {
            super.put(key, value);
        } catch (JSONException ignored) {}
        return this;
    }

    @Override
    public SafeJsonObject put(String key, Object value) {
        try {
            super.put(key, value);
        } catch (JSONException ignored) {}
        return this;
    }
}
