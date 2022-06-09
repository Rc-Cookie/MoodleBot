package de.rwth.discord.moodle;

import java.util.Collection;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import com.github.rccookie.util.Console;

/**
 * Generic base class for moodle bots.
 */
public abstract class AbstractMoodleBot {

    /**
     * Interval between two updates of the same course.
     */
    private final int interval;
    /**
     * The courses to check for.
     */
    private final int[] courses;

    /**
     * Creates a new abstract moodle bot.
     *
     * @param interval The interval between two updates of the same course, in seconds
     * @param courses The ids of the courses to monitor
     */
    public AbstractMoodleBot(int interval, int... courses) {
        Console.write("PID", ProcessHandle.current().pid());
        this.interval = interval;
        this.courses = courses;
    }

    /**
     * Starts the bot. May be called at the end of the constructor.
     */
    protected void start() {
        ScheduledExecutorService executor = Executors.newSingleThreadScheduledExecutor();
        for(int i=0; i<courses.length; i++) {
            CourseChangeListener listener = new CourseChangeListener(courses[i], this::handleNewFiles, this::handleDeadlineFiles);
            executor.scheduleAtFixedRate(() -> {
                beforeCheck(listener.course);
                listener.run();
                afterCheck(listener.course);
            }, (interval / courses.length) * (long) i, interval, TimeUnit.SECONDS);
        }
    }

    /**
     * Called immediately before a listener updates. Default implementation
     * does nothing.
     *
     * @param course The course to be updates
     */
    protected void beforeCheck(Course course) {
    }

    /**
     * Called immediately after a listener updates. Default implementation
     * does nothing.
     *
     * @param course The course to be updates
     */
    protected void afterCheck(Course course) {
    }

    /**
     * Called when new files are found.
     *
     * @param files The new files found, as file tree
     */
    protected void handleNewFiles(File files) {
        handleNewFiles(new Course(files.name, Integer.parseInt(files.description)), List.of(files.getFiles()));
    }

    /**
     * Called when new files are found.
     *
     * @param course The course that the files were found in
     * @param files The found files (not as file tree, just the files themselves)
     */
    protected void handleNewFiles(Course course, Collection<File> files) {
        Console.splitCustom("debug", "New files");
        files.stream().map(f->f+"\n").forEach(Console::debug);
        handleFiles(course, files, "Neue Datei wurde hochgeladen:", "Neue Dateien wurden hochgeladen:", true);
    }

    /**
     * Called when files close to deadline are found.
     *
     * @param course The course that the files were found in
     * @param files The found files (not as file tree, just the files themselves)
     */
    protected void handleDeadlineFiles(Course course, Collection<File> files) {
        Console.splitCustom("debug", "Deadline files");
        files.stream().map(f->f+"\n").forEach(Console::debug);
        handleFiles(course, files, "Abgabe endet bald:", "Abgaben enden bald:", false);
    }

    /**
     * Called when files that should be reported are found.
     *
     * @param course The course that the files were found in
     * @param files The found files (not as file tree, just the files themselves)
     * @param descSing The description of the event, in singular
     * @param descPlural The description of the event, in plural
     * @param uploadFiles Whether the files should be attached if possible
     */
    protected abstract void handleFiles(Course course, Collection<File> files, String descSing, String descPlural, boolean uploadFiles);
}
