package de.rwth.discord.moodle;

public final class Course {

    public final String name;
    public final int id;
    public final String url;

    public Course(String name, int id) {
        this.name = name;
        this.id = id;
        this.url = "https://moodle.rwth-aachen.de/course/view.php?id="+id;
    }
}
