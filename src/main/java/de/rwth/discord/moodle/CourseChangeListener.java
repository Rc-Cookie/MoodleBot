package de.rwth.discord.moodle;

import java.text.DateFormat;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.function.BiConsumer;
import java.util.function.BiFunction;
import java.util.function.Consumer;

import com.github.rccookie.json.Json;
import com.github.rccookie.json.JsonObject;
import com.github.rccookie.util.Console;
import com.github.rccookie.util.http.HTTPRequest;
import com.github.rccookie.util.http.HTTPResponse;
import com.github.rccookie.xml.Node;
import com.github.rccookie.xml.XML;
import com.github.rccookie.xml.XMLParser;

import org.jetbrains.annotations.NotNull;

/**
 * A runnable that checks for new files in a specified moodle course.
 */
public class CourseChangeListener implements Runnable {

    /**
     * The course that this listener checks.
     */
    public final Course course;

    /**
     * Listener for new files.
     */
    private final Consumer<File> diffListener;
    /**
     * Listener for files that are close to their deadline.
     */
    private final BiConsumer<Course, Collection<File>> deadlineListener;


    /**
     * Accumulates the received bytes of the current iteration.
     */
    private int traffic = 0;

    /**
     * The timestamp to use for the current iteration.
     */
    private long currentTime;


    /**
     * Creates a new course change listener for the specified course.
     *
     * @param course The id of the course to check
     * @param diffListener The callback to use when new files are found
     * @param deadlineListener The callback to use when files are close to their deadline
     */
    public CourseChangeListener(int course, Consumer<File> diffListener, BiConsumer<Course, Collection<File>> deadlineListener) {
        this.diffListener = diffListener;
        this.deadlineListener = deadlineListener;
        this.course = new Course(getCourseName(course), course);
    }

    /**
     * Retrieves the name of the specified course.
     *
     * @param id The id of the course to get the name for
     * @return The name of that course
     */
    private static String getCourseName(int id) {
        HTTPResponse r = newHttpRequest("https://moodle.rwth-aachen.de/course/resources.php?id="+id)
                .send().waitFor();
        String s = r.data.substring(r.data.indexOf("<title>") + 7);
        String title = s.substring(0, s.indexOf("</title>"));
        title = title.replaceFirst("^\\([A-Z]+\\)", "");
        return title.substring(0, title.indexOf(':')).replace("&amp;", "&").strip();
    }

    // ---------------------------------------------------

    /**
     * Runs the change listener. If changes are found this method will
     * call the callback listeners.
     */
    @Override
    public synchronized void run() {
        Console.logTime("Checking", course.name + "...");
        currentTime = System.currentTimeMillis();
        traffic = 0;

        File currentFiles = getCurrentFiles();
        JsonObject storedFiles = getStoredFiles();
        File oldFiles = storedFiles.getElement(course.id+"").as(File.class);
        File diff = currentFiles.diff(oldFiles, false);

        storedFiles.put(""+ course.id, currentFiles);
        // Combine with old files: don't accidentally delete all stored data
        storedFiles.getObject(course.id+"").combine((JsonObject) oldFiles.toJson());
        Json.store(storedFiles, new java.io.File("files.json"));

        if(diff != null) {
            try {
                diffListener.accept(diff);
            } catch(Exception e) {
                Console.error("Exception in diff listener");
                Console.error(e);
            }
        }
        else Console.debug("No diff");

        long offset = 16 * 60 * 60 * 1000L;
        long threshold = currentTime + offset;
        List<File> timedFiles = new ArrayList<>();
        for(File file : oldFiles.getFiles()) {
            if(file.deadline == null || file.deadline == -1) continue;
            if(file.deadline >= currentTime && file.deadline <= threshold && file.deadline > file.lastCheck + offset)
                timedFiles.add(file);
        }
        if(!timedFiles.isEmpty())
            deadlineListener.accept(course, timedFiles);
        else Console.debug("No new critical deadlines");

        Console.logTime("Done: {} new files, {} deadline files", diff != null ? diff.getFiles().length : 0, timedFiles.size());
        Console.mapDebug("HTTP Traffic", traffic / 1000f, "KB");
        System.gc();
    }

    // ---------------------------------------------------

    /**
     * Loads the stored file history from disk, if present. Otherwise, an appropriate
     * empty file tree stump will be returned. The returned json object is ensured to
     * have a mapping for the id of this course as string to a file with information
     * about this course.
     *
     * @return The stored files
     */
    public JsonObject getStoredFiles() {
        JsonObject storedFiles;
        try {
            storedFiles = Json.load("files.json").asObject();
            if(storedFiles.containsKey(course.id+""))
                return storedFiles;
        } catch(Exception e) {
            Console.warn(e);
            storedFiles = new JsonObject();
        }
        File oldFiles = new File();
        oldFiles.name = course.name;
        oldFiles.description = course.id+"";
        oldFiles.type = "folder";
        oldFiles.url = course.url;
        storedFiles.put(course.id+"", oldFiles);
        return storedFiles;
    }

    // ---------------------------------------------------

    /**
     * Fetches the currently available files. WARNING: This may not include any actual files
     * when moodle does not work or is under maintenance.
     *
     * @return The currently available files
     */
    @NotNull
    public File getCurrentFiles() {
        File currentFiles = new File();
        currentFiles.type = "folder";
        currentFiles.name = course.name;
        currentFiles.description = course.id+"";
        currentFiles.url = "https://moodle.rwth-aachen.de/course/view.php?id=" + course.id;

        currentFiles.add(getCurrentResources());
        currentFiles.add(getCurrentTasks());
        currentFiles.add(getCurrentTests());

        return currentFiles;
    }

    /**
     * Fetches the currently available files in the resources tab of the course.
     *
     * @return The currently available resource file tree
     */
    private File getCurrentResources() {
        return parseTablePage("resources", "https://moodle.rwth-aachen.de/course/resources.php", this::addResourceEntry);
    }

    /**
     * Fetches the currently available tasks in the tasks tab of the course.
     *
     * @return The currently available tasks
     */
    private File getCurrentTasks() {
        return parseTablePage("tasks", "https://moodle.rwth-aachen.de/mod/assign/index.php", (e,c) -> addTaskOrTest(e, c, "task"));
    }

    /**
     * Fetches the currently available tests in the tests tab of the course.
     *
     * @return The currently available tests
     */
    private File getCurrentTests() {
        return parseTablePage("tests", "https://moodle.rwth-aachen.de/mod/quiz/index.php", (e,c) -> addTaskOrTest(e, c, "test"));
    }

    /**
     * Parses the file tree for the given moodle page with a table as main content. Each
     * entry will be parsed using the specified entry parser.
     *
     * @param name The name for the produced file tree
     * @param url The url of the produced file tree
     * @param entryParser The parser applied to each row of the table. The parser takes the
     *                    html of the row and the file to add the parsed file tree to as
     *                    parameters and returns the section name (the value in the first
     *                    column), if present
     * @return The parsed file tree
     */
    private File parseTablePage(String name, String url, BiFunction<String, File, String> entryParser) {
        HTTPResponse r = newCourseHttpRequest(url)
                .send().waitFor();
        traffic += r.bytes.length;

        File folder = new File();
        folder.name = name;
        folder.url = url + "?id=" + course.id;
        folder.type = "folder";

        int end = r.data.indexOf("</tbody>");
        if(end == -1) return folder;
        for(String section : ("\"></div></td></tr>\n" + r.data.substring(r.data.indexOf("<tbody>") + 7, r.data.indexOf("</tbody>"))).split("tabledivider")) {

            File sectionFile = new File();
            sectionFile.type = "folder";

            String[] entries = section.split("</tr>");
            for(int i=1; i<entries.length-1; i++) {
                String n = entryParser.apply(entries[i], sectionFile);
                if(sectionFile.name == null) sectionFile.name = n;
            }

            folder.add(sectionFile);
        }
        return folder;
    }

    /**
     * Parses a table row on a resource page and adds it to the specified container.
     * This also includes the file tree of folders and subfolders.
     *
     * @param entry The table row string
     * @param container The file to add the generated file tree to
     * @return The name of the section
     */
    private String addResourceEntry(String entry, File container) {

        String rem = entry.substring(entry.indexOf("<td class=\"cell c0\" style=\"text-align:center;\">") + 47);
        String name = rem.substring(0, rem.indexOf("</td>"));

        File file = new File();

        rem = rem.substring(rem.indexOf("href=\"") + 6);
        file.url = rem.substring(0, rem.indexOf('"'));

        rem = rem.substring(rem.indexOf("src=\"") + 5);
        file.setTypeFromImageUrl(rem.substring(0, rem.indexOf('"')));

        rem = rem.substring(rem.indexOf("/>") + 3); // There's a space after '/>'
        file.name = rem.substring(0, rem.indexOf("</a>")).replace("&amp;", "&");

        int descriptionStart = rem.indexOf("<div class=\"no-overflow\">");
        if(descriptionStart != -1) {
            rem = rem.substring(descriptionStart, rem.indexOf("</div>", descriptionStart) + 6).replace("<br>", "\n");
            file.description = XML.getParser(rem, XMLParser.HTML).next().getText();
        }

        if(file.type.equals("folder"))
            file = getFolderContents(file.name, file.url);

        container.add(file);
        return name;
    }

    /**
     * Fetches the file tree for the folder at the specified url.
     *
     * @param name The name for the produces file tree
     * @param url The url of the folder to parse
     * @return The file tree of the folder
     */
    private File getFolderContents(String name, String url) {

        HTTPResponse r = newHttpRequest(url).send().waitFor();
        traffic += r.bytes.length;
        String files = r.data.substring(r.data.indexOf("class=\"filemanager\">") + 20);

        File folder = parseFileTree(XML.getParser(files).next().children.get(0));
        folder.name = name;
        folder.url = url;
        return folder;
    }

    /**
     * Parses the file tree represented by the specified node.
     *
     * @param node The file tree root (an li element)
     * @return The parsed file tree
     */
    private File parseFileTree(Node node) {
        File file = new File();
        file.name = node.children.get(0).getText();

        if(node.children.size() > 1) {
            file.type = "folder";
            for(Node subFile : node.children.get(1))
                file.add(parseFileTree(subFile));
        }
        else {
            file.setTypeFromImageUrl(node.children.get(0).children.get(0)
                    .children.get(0).children.get(0).attributes.get("src"));
            file.url = node.children.get(0).children.get(0).attributes.get("href");
        }
        return file;
    }

    /**
     * Parses a table row on a tasks or tests page and adds it to the specified
     * container file.
     *
     * @param entry The table row html
     * @param container The container to add to
     * @param type The type for the parsed files (-> task or test)
     * @return The section name
     */
    private String addTaskOrTest(String entry, File container, String type) {

        String rem = entry.substring(entry.indexOf(";\">")+3);
        String sectionName = rem.substring(0, rem.indexOf("</td>"));

        File file = new File();
        file.type = type;

        rem = rem.substring(rem.indexOf("href=\"")+6);
        file.url = rem.substring(0, rem.indexOf('"'));
        if(!file.url.startsWith("https"))
            file.url = "https://moodle.rwth-aachen.de/" + file.url;

        rem = rem.substring(rem.indexOf("\">")+2);
        file.name = rem.substring(0, rem.indexOf("</a>")).replace("&amp;", "&");

        rem = rem.substring(rem.indexOf(";\">")+3);
        file.deadline = parseTime(rem.substring(0, rem.indexOf("</td>")));
        file.lastCheck = currentTime;

        container.add(file);

        return sectionName;
    }

    /**
     * Parser for dates on the moodle page.
     */
    @SuppressWarnings("SpellCheckingInspection")
    private static final DateFormat DATE_FORMAT = new SimpleDateFormat("EEEEEEE, dd. MMMMM yyyy, HH:mm", Locale.GERMAN);

    /**
     * Parses the timestamp from the given date string.
     *
     * @param time The date string, in the format DAY_W, DAY. MONTH YEAR, HOUR:MINUTE
     * @return The corresponding time stamp
     */
    private static long parseTime(String time) {
        if(time.equals("-")) return -1;
        try {
            return DATE_FORMAT.parse(time).getTime();
        } catch(Exception e) {
            throw new RuntimeException(e);
        }
    }

    // ---------------------------------------------------

    /**
     * Creates a new http request for the specified url with '?id=[course.id]' appended
     * and with a valid moodle login cookie set.
     *
     * @param url The request url
     * @return The http request
     */
    private HTTPRequest newCourseHttpRequest(String url) {
        return newHttpRequest(url + "?id=" + course.id);
    }

    /**
     * Creates a new http request with a valid moodle login cookie set.
     *
     * @param url The request url
     * @return The http request
     */
    private static HTTPRequest newHttpRequest(String url) {
        Console.mapDebug("Fetching", url);
        return new HTTPRequest(url).setCookies(MoodleCookie.COOKIE);
    }
}
