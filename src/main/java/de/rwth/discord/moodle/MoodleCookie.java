package de.rwth.discord.moodle;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;

import com.github.rccookie.util.Console;
import com.github.rccookie.util.http.HTTPRequest;
import com.github.rccookie.util.http.HTTPResponse;

/**
 * Utility class with a single field {@link #COOKIE}; a valid moodle login cookie.
 * Initializing the class will load the cookie.
 */
public final class MoodleCookie {

    private MoodleCookie() {
        throw new UnsupportedOperationException();
    }

    /**
     * A valid moodle login cookie.
     */
    public static final String COOKIE = getCookie();

    /**
     * Loads a cached moodle cookie from disk. If no cached cookie was
     * found, or it isn't valid (anymore), a new cookie will be loaded
     * and cached.
     *
     * @return A valid moodle login cookie
     */
    private static String getCookie() {
        try {
            String cookie;
            Path cookieFile = Path.of("moodlelogin.cookie");
            if(!Files.exists(cookieFile) || !testCookie(cookie = Files.readString(cookieFile)))
                Files.writeString(cookieFile, cookie = MoodleLogin.getCookie());
            return cookie;
        } catch(IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /**
     * Tests whether the given moodle cookie is valid, meaning it allows logging in.
     *
     * @param cookie The cookie to test
     * @return Whether the given cookie is valid
     */
    private static boolean testCookie(String cookie) {
        Console.mapDebug("Fetching", "https://moodle.rwth-aachen.de/user/files.php");
        HTTPResponse r = new HTTPRequest("https://moodle.rwth-aachen.de/user/files.php")
                .setCookies(cookie)
                .send().waitFor();
        return r.data.substring(0, 200).contains("<title>Meine Dateien</title>");
    }
}
