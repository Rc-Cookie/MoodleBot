package de.rwth.discord.moodle;

import java.util.logging.Level;
import java.util.logging.Logger;

import com.github.rccookie.util.ArgsParser;
import com.github.rccookie.util.Console;
import com.github.rccookie.util.login.Login;
import com.github.rccookie.util.login.Passwords;

import org.openqa.selenium.By;
import org.openqa.selenium.WebDriver;
import org.openqa.selenium.htmlunit.HtmlUnitDriver;

/**
 * Utility class for obtaining a valid moodle login cookie.
 */
public final class MoodleLogin {

    static {
        Logger.getLogger("org.openqa.selenium").setLevel(Level.OFF);
        Logger.getLogger("org.slf4g.impl").setLevel(Level.OFF);
        Logger.getLogger("com.gargoylesoftware").setLevel(Level.OFF);
        System.setProperty("org.apache.commons.logging.Log", "org.apache.commons.logging.impl.NoOpLog");
    }

    private MoodleLogin() {
    }

    /**
     * Generates a new moodle login cookie using the given login credentials.
     *
     * @param login The login credentials to use on sso.rwth-aachen.de
     * @return The login cookie, in the form "MoodleSession=..."
     */
    public static String getCookie(Login login) {
        Console.debug("Starting driver");
        WebDriver driver = new HtmlUnitDriver(true);
        Console.debug("Driver started");

        driver.get("https://moodle.rwth-aachen.de/auth/shibboleth/index.php");

        Console.debug("On login page");

        driver.findElement(By.id("username")).sendKeys(login.username);
        driver.findElement(By.id("password")).sendKeys(login.password);
        driver.findElement(By.id("login")).click();

        Console.debug("Logged in");

        while(driver.manage().getCookieNamed("MoodleSession") == null)
            Thread.onSpinWait();

        String sessionCookie = "MoodleSession=" + driver.manage().getCookieNamed("MoodleSession").getValue();
        driver.close();

        System.gc();
        Console.mapDebug("Login Cookie", sessionCookie);

        return sessionCookie;
    }

    /**
     * Generates a new moodle login cookie using the credentials for
     * sso.rwth-aachen.de.
     *
     * @return The login cookie, in the form "MoodleSession=..."
     */
    public static String getCookie() {
        return getCookie(Passwords.get("sso.rwth-aachen.de"));
    }


    /**
     * Standalone program that generates a new moodle login cookie.
     */
    public static void main(String[] args) {
        ArgsParser parser = new ArgsParser();
        parser.setName("Moodle Login Manager");
        parser.setDescription("Fetches a login cookie for the moodle page");
        parser.addDefaults();
        parser.parse(args);
        System.out.println(getCookie());
    }
}
