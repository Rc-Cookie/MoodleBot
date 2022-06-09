package de.rwth.discord.moodle;

import java.util.ArrayList;
import java.util.Calendar;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

import com.github.rccookie.json.JsonDeserialization;
import com.github.rccookie.json.JsonObject;
import com.github.rccookie.json.JsonSerializable;
import com.github.rccookie.util.Utils;
import com.github.rccookie.util.http.HTTPRequest;

import org.jetbrains.annotations.NotNull;

/**
 * Represents a file or folder on the moodle page.
 */
public class File implements Iterable<File>, JsonSerializable {

    // Initialize json deserialization
    static {
        JsonDeserialization.register(File.class, json -> {
            File file = new File();
            file.name = json.get("name").asString();
            file.type = json.get("type").asString();
            json.get("url").toOptional().ifPresent(u -> file.url = u.asString());
            json.get("description").toOptional().ifPresent(d -> file.description = d.asString());
            json.get("children").toOptional().ifPresent(c -> file.setChildren(c.as(File[].class)));
            json.get("deadline").toOptional().ifPresent(d -> file.deadline = d.asLong());
            json.get("lastCheck").toOptional().ifPresent(d -> file.lastCheck = d.asLong());
            return file;
        });
    }

    /**
     * Maps common types to a simple display name.
     */
    private static final Map<String, String> SIMPLE_TYPE_MAPPING = Utils.map(
            "pdf", "Datei",
            "page", "Seite",
            "url", "Seite",
            "folder", "Ordner",
            "sourcecode", "Datei",
            "archive", "Datei",
            "task", "Aufgabe",
            "test", "Test"
    );

    /**
     * Set of types of files that may be loaded and attached to a message.
     */
    private static final Set<String> LOADABLE_TYPES = Set.of("pdf", "txt");


    /**
     * Name of the file.
     */
    public String name;
    /**
     * Type of the file, for example 'folder' or 'pdf'.
     */
    public String type;
    /**
     * Url to the file, if any.
     */
    public String url;
    /**
     * Description of the file, if any.
     */
    public String description;
    /**
     * Deadline timestamp of the file, if the file has a deadline.
     */
    public Long deadline;
    /**
     * Last time the deadline was checked.
     */
    public Long lastCheck;

    /**
     * Files contained in this folder. The type should be 'folder' if this is used.
     */
    private final List<File> children = new ArrayList<>();


    /**
     * Creates a new file.
     */
    public File() {
    }

    /**
     * Creates a new file
     *
     * @param name The name to use
     * @param type The type to use
     * @param url The url to use
     * @param description The description to use
     */
    private File(String name, String type, String url, String description) {
        this.name = name;
        this.type = type;
        this.url = url;
        this.description = description;
    }

    @Override
    public boolean equals(Object o) {
        if(this == o) return true;
        if(!(o instanceof File file)) return false;
        return equalsLocal(file) && Objects.equals(children, file.children);
    }

    /**
     * Similar to {@link #equals(Object)}, but children equality is ignored.
     *
     * @param f The file to test for equality
     * @return Whether the files are equal locally
     */
    private boolean equalsLocal(File f) {
        if(this == f) return true;
        return Objects.equals(name, f.name) && Objects.equals(type, f.type)
                && Objects.equals(url, f.url) && Objects.equals(description, f.description)
                && Objects.equals(deadline, f.deadline);
    }

    @Override
    public int hashCode() {
        return Objects.hash(name, type, url, description, children, deadline);
    }

    @Override
    public String toString() {
        return toJson().toString();
    }


    /**
     * Adds the given file as child of this file
     *
     * @param child The file to add
     */
    public void add(File child) {
        children.add(child);
    }

    /**
     * Sets the children of this file to the specified files.
     *
     * @param children The children for the file
     */
    private void setChildren(File[] children) {
        this.children.clear();
        for(File child : children) add(child);
    }

    /**
     * Parses the file type from the given icon url and sets it.
     *
     * @param url The url of the thumbnail icon
     */
    public void setTypeFromImageUrl(String url) {
        String partUrl = url.substring(64);
        if(partUrl.startsWith("core"))
            type = partUrl.substring(partUrl.lastIndexOf('/')+1, partUrl.lastIndexOf('-'));
        else type = partUrl.substring(0, partUrl.indexOf('/'));
    }

    /**
     * Returns a simple name for the type of this file.
     *
     * @return A simple type name
     */
    public String getSimpleTypeName() {
        return SIMPLE_TYPE_MAPPING.getOrDefault(type, "Datei");
    }

    /**
     * Returns the name of this file, ensuring that a suffix is included.
     *
     * @return The filename for this file
     */
    public String getFileName() {
        return name + (name.endsWith("."+type) ? "" : "." + type);
    }

    /**
     * Returns a date string for the file's deadline.
     *
     * @return The file's deadline, as date string formatted
     */
    public String getDeadlineDate() {
        Calendar calendar = Calendar.getInstance();
        calendar.setTimeInMillis(deadline);
        return calendar.getDisplayName(Calendar.DAY_OF_WEEK, Calendar.LONG, Locale.GERMAN)
                + ", " + calendar.get(Calendar.DAY_OF_MONTH)
                + ". " + (calendar.get(Calendar.MONTH)+1)
                + ". " + String.format("%02d:%02d", calendar.get(Calendar.HOUR_OF_DAY), calendar.get(Calendar.MINUTE));
    }

    /**
     * Returns a descriptive markdown formatted string for this file. This does
     * not include a title.
     *
     * @return A markdown description for this file
     */
    public String getMarkdownDescription() {
        List<String> lines = new ArrayList<>();
        if(url != null)
            lines.add("[" + getSimpleTypeName() + " \u00F6ffnen](" + getFinalUrl() + ")");
        if(description != null)
            lines.add(description);
        if(deadline != null)
            lines.add("Fällig " + getDeadlineDate());
        lines.add("\u200b");
        return String.join("\n", lines);
    }

    /**
     * Returns the final url of this file, which is the url that the file's url redirects to,
     * if it does.
     *
     * @return The final redirected url of this file's url
     */
    public String getFinalUrl() {
        String url = this.url;
        while(!url.endsWith("forcedownload=1")) {
            String newUrl = new HTTPRequest(url)
                    .setMethod(HTTPRequest.Method.HEAD)
                    .setCookies(MoodleCookie.COOKIE)
                    .allowRedirects(false)
                    .send().waitFor().header.get("Location");
            if(newUrl == null || newUrl.equals(url)) break;
            url = newUrl;
        }
        return url;
    }

    /**
     * Returns whether this file can be downloaded and attached to a message.
     *
     * @return Whether this file is loadable
     */
    public boolean isLoadable() {
        return url != null && LOADABLE_TYPES.contains(type);
    }

    /**
     * Downloads whatever the file's url points to and returns the downloaded bytes.
     *
     * @return The downloaded data
     */
    public byte[] loadDocument() {
        return new HTTPRequest(url)
                .setCookies(MoodleCookie.COOKIE)
                .send().waitFor().bytes;
    }

    /**
     * Returns an iterator over the files children.
     *
     * @return An iterator over the children
     */
    @NotNull
    @Override
    public Iterator<File> iterator() {
        return children.iterator();
    }

    @Override
    public Object toJson() {
        JsonObject json = new JsonObject("name", name, "type", type);
        if(url != null) json.put("url", url);
        if(description != null) json.put("description", description);
        if(!children.isEmpty()) json.put("children", children);
        if(deadline != null) json.put("deadline", deadline);
        if(lastCheck != null) json.put("lastCheck", lastCheck);
        return json;
    }

    /**
     * Computes the difference from this file tree to the given file tree.
     * The file tree is preserved, the order inside files is ignored. If
     * symmetric is false, only additional files in this file tree are returned,
     * otherwise also missing files (aka additional files in the other file
     * tree).
     *
     * @param other The file tree to compare to
     * @param symmetric Whether to compute the symmetric difference, or only
     *                  one-sided
     * @return The difference file tree
     */
    public File diff(File other, boolean symmetric) {
        if(other == null) return this;
        if(!equalsLocal(other)) return this;

        File diff = new File(name, type, url, description);

        List<File> rem = new ArrayList<>(children), remOther = new ArrayList<>(other.children);
        while(!rem.isEmpty()) {
            File child = rem.remove(0);
            File oChild = null;
            for(File o : remOther) {
                if(child.equalsLocal(o)) {
                    oChild = o;
                    remOther.remove(o);
                    break;
                }
            }
            if(oChild == null) diff.add(child);
            else {
                File innerDiff = child.diff(oChild, symmetric);
                if(innerDiff != null) diff.add(innerDiff);
            }
        }
        if(symmetric)
            diff.children.addAll(remOther);

        return diff.children.isEmpty() ? null : diff;
    }

    /**
     * Returns the files in this file tree that are actual files and
     * not folders, in other words, the leafs of this file tree.
     *
     * @return The files in this file tree
     */
    public File[] getFiles() {
        List<File> files = new ArrayList<>();
        addFiles(files);
        return files.toArray(new File[0]);
    }

    /**
     * Adds this file tree's leaf files into the specified list
     *
     * @param list The file to add to
     */
    private void addFiles(List<File> list) {
        if(children.isEmpty()) list.add(this);
        else for(File child : this) child.addFiles(list);
    }
}
