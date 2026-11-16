package com.bkggds.denrecorder;

import android.content.Context;
import android.content.res.Configuration;

import java.util.Locale;

/** Keeps the app's UI in the language picked in Settings (English by default). */
public final class LocaleHelper {

    public static final String PREFS = "settings";
    public static final String KEY = "language";

    private LocaleHelper() { }

    public static Context wrap(Context base) {
        String tag = base.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getString(KEY, "en");
        if (tag == null || tag.isEmpty()) tag = "en";
        Locale locale = new Locale(tag);
        Locale.setDefault(locale);
        Configuration config = new Configuration(base.getResources().getConfiguration());
        config.setLocale(locale);
        return base.createConfigurationContext(config);
    }

    public static String str(Context context, int id) {
        return wrap(context).getString(id);
    }
}
