package com.bkggds.denrecorder;

import android.content.Context;
import android.content.res.Configuration;

import java.util.Locale;

/** Keeps the app's UI in the language picked in Settings. */
public final class LocaleHelper {

    public static final String PREFS = "settings";
    public static final String KEY = "language";

    /** Every language the app has texts for, in the order Settings lists them. */
    static final String[][] LANGUAGES = {
            {"en", "English"},
            {"ar", "العربية"},
            {"zh", "中文"},
            {"nl", "Nederlands"},
            {"fr", "Français"},
            {"de", "Deutsch"},
            {"hi", "हिन्दी"},
            {"id", "Bahasa Indonesia"},
            {"it", "Italiano"},
            {"ja", "日本語"},
            {"ko", "한국어"},
            {"pl", "Polski"},
            {"pt", "Português"},
            {"ru", "Русский"},
            {"es", "Español"},
            {"th", "ไทย"},
            {"tr", "Türkçe"},
            {"uk", "Українська"},
            {"vi", "Tiếng Việt"}
    };

    private LocaleHelper() { }

    /** The language picked in Settings. */
    public static String chosen(Context context) {
        String tag = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getString(KEY, "en");
        if (tag == null || tag.isEmpty() || "system".equals(tag)) tag = "en";
        return tag;
    }

    /** The name a language calls itself in, e.g. "Español". */
    public static String nativeName(String tag) {
        for (String[] entry : LANGUAGES) {
            if (entry[0].equals(tag)) return entry[1];
        }
        return "English";
    }

    public static Context wrap(Context base) {
        String tag = chosen(base);
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
