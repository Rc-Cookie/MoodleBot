package de.rwth.discord.moodle;

import com.github.rccookie.util.Console;

import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.Role;
import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent;
import net.dv8tion.jda.api.hooks.ListenerAdapter;
import org.jetbrains.annotations.NotNull;

/**
 * Listener to check for /sub and /unsub commands.
 */
@SuppressWarnings("ConstantConditions")
public class SubscriptionManager extends ListenerAdapter {

    /**
     * Creates a new subscription manager.
     *
     * @param jda The jda for the commands to run on
     */
    public SubscriptionManager(JDA jda) {
        jda.upsertCommand("sub", "Subscribe to Moodle updates").queue();
        jda.upsertCommand("unsub", "Unsubscribe from Moodle updates").queue();
    }

    @Override
    public void onSlashCommandInteraction(@NotNull SlashCommandInteractionEvent event) {
        String cmd = event.getInteraction().getName();
        if(cmd.equals("sub")) subscribe(event);
        else if(cmd.equals("unsub")) unsubscribe(event);
        else Console.warn("Unknown command:", cmd);
    }

    /**
     * Adds the moodle notification role to the member causing the event, if not already
     * present.
     *
     * @param event The event that initiated the subscription call
     */
    private void subscribe(SlashCommandInteractionEvent event) {
        Role role = getMoodleRole(event.getMember().getGuild());

        if(event.getMember().getRoles().stream().anyMatch(r -> r.getIdLong() == role.getIdLong())) {
            event.reply("Already subscribed").setEphemeral(true).queue();
            return;
        }
        event.getMember().getGuild().addRoleToMember(event.getMember(), role)
                .flatMap($ -> event.reply("Subscription added \u2705").setEphemeral(true)).queue();
        Console.log("Adding subscription for", event.getMember().getEffectiveName());
    }

    /**
     * Toggles the moodle notification role to the member causing the event.
     *
     * @param event The event that initiated the subscription call
     */
    private void unsubscribe(SlashCommandInteractionEvent event) {
        Role role = getMoodleRole(event.getMember().getGuild());

        if(event.getMember().getRoles().stream().allMatch(r -> r.getIdLong() != role.getIdLong())) {
            subscribe(event);
            return;
        }
        event.getMember().getGuild().removeRoleFromMember(event.getMember(), role)
                .flatMap($ -> event.reply("Subscription removed").setEphemeral(true)).queue();
        Console.log("Removing subscription for", event.getMember().getEffectiveName());
    }

    /**
     * Finds the moodle notification role in the given server.
     *
     * @param guild The server to find the role in
     * @return The moodle notification role
     */
    public static Role getMoodleRole(Guild guild) {
        return guild.getRolesByName("Moodle", true)
                .stream().findAny().orElseGet(() -> {
                        Console.warn("No Moodle role found. Creating new");
                        Role role = guild.createRole().setColor(0xf5811f).setName("Moodle").complete();
                        guild.addRoleToMember(guild.getSelfMember(), role).complete();
                        return role;
                });
    }
}
