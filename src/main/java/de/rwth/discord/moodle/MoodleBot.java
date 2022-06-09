package de.rwth.discord.moodle;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;

import javax.security.auth.login.LoginException;

import com.github.rccookie.util.Args;
import com.github.rccookie.util.ArgsParser;
import com.github.rccookie.util.Console;
import com.github.rccookie.util.Utils;

import net.dv8tion.jda.api.EmbedBuilder;
import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.JDABuilder;
import net.dv8tion.jda.api.OnlineStatus;
import net.dv8tion.jda.api.entities.Activity;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.MessageChannel;
import net.dv8tion.jda.api.entities.MessageEmbed;
import net.dv8tion.jda.api.requests.restaction.MessageAction;

/**
 * Moodle bot using a full discord bot.
 */
public class MoodleBot extends AbstractMoodleBot {

    /**
     * The channel to post in.
     */
    private final MessageChannel channel;
    /**
     * The server that the channel is within.
     */
    private final Guild guild;
    /**
     * The jda instance.
     */
    private final JDA jda;


    /**
     * Creates a new MoodleBot for the specified courses posing into the specified channel.
     *
     * @param channelID The id of the channel to post updates to
     * @param interval The update interval of a single course
     * @param courses The courses to monitor
     */
    public MoodleBot(long channelID, int interval, int... courses) throws LoginException, InterruptedException {
        super(interval, courses);

        //noinspection ConstantConditions
        jda = JDABuilder.createLight(Utils.readAll(MoodleBot.class.getClassLoader().getResourceAsStream("bot.token")))
                .build().awaitReady();
        channel = jda.getChannelById(MessageChannel.class, channelID);
        if(channel == null) throw new IllegalArgumentException("Channel not found: " + channelID);

        Guild guild = null;
        for(Guild g : jda.getGuilds()) {
            if(g.getChannelById(MessageChannel.class, channelID) != null) {
                guild = g;
                break;
            }
        }
        this.guild = guild;
        if(guild == null) throw new AssertionError();

        jda.addEventListener(new SubscriptionManager(jda));
        start();
    }

    @Override
    protected void beforeCheck(Course course) {
        jda.getPresence().setPresence(OnlineStatus.ONLINE, Activity.playing(course.name));
    }

    @Override
    protected void afterCheck(Course course) {
        jda.getPresence().setStatus(OnlineStatus.IDLE);
    }

    @Override
    protected void handleFiles(Course course, Collection<File> files, String descSing, String descPlural, boolean uploadFiles) {

        channel.sendTyping().queue();

        EmbedBuilder embed = new EmbedBuilder();
        embed.setTitle(course.name, course.url);
        embed.setFooter("Use /sub to receive notifications"); // MoodleBot by RcCookie •
        embed.setColor(0xf47f22);
        embed.setTimestamp(Instant.now());

        File[] fs = files.stream().limit(25).toArray(File[]::new);

        embed.setDescription((fs.length == 1 ? descSing: descPlural) + "\n\u200b");

        for(File file : fs)
            embed.addField(new MessageEmbed.Field(file.name, file.getMarkdownDescription(), false));

        List<MessageAction> actions = new ArrayList<>();
        actions.add(channel.sendMessage("<@&" + SubscriptionManager.getMoodleRole(guild).getId() + ">").setEmbeds(embed.build()));

        if(uploadFiles) {
            int currentSize = 8000000;
            int currentCount = 0;

            for(File file : fs) {
                if(!file.isLoadable()) continue;
                byte[] bytes = file.loadDocument();
                if(bytes.length > 8000000) {
                    Console.warn(file.name + ":", "Too big for upload ({} MB)", bytes.length / 1000000f);
                    continue;
                }
                if((currentSize += bytes.length) > 8000000 || currentCount >= 10) {
                    actions.add(channel.sendFile(bytes, file.getFileName()));
                    currentSize = bytes.length;
                    currentCount = 0;
                }
                else actions.set(actions.size()-1, actions.get(actions.size()-1).addFile(bytes, file.getFileName()));
                currentCount++;
            }
        }

        for(MessageAction action : actions)
            action.complete();
    }



    public static void main(String[] args) throws LoginException, InterruptedException {
        ArgsParser parser = new ArgsParser();
        parser.addDefaults();
        parser.setName("MoodleBot");
        parser.setDescription("Usage: moodleBot -c <channel> <options> courseIDs...");
        parser.addOption('c', "channel", true, "ID of the channel to send notifications to (required)");
        parser.addOption('i', "interval", true, "Interval in seconds between two checks for the same course. Default is 300");
        Args options = parser.parse(args);
        if(options.getArgs().length == 0) {
            Console.warn("No courses specified");
            parser.showHelp(); // Automatically quits
        }
        if(!options.is("channel")) {
            Console.error("Missing options -c / --channel. Use --help for more information");
            System.exit(1);
        }

        new MoodleBot(
                options.getLong("channel"),
                options.getIntOr("interval", 300),
                Arrays.stream(options.getArgs()).mapToInt(Integer::parseInt).toArray()
        );
    }
}
