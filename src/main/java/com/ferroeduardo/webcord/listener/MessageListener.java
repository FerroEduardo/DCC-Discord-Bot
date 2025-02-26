package com.ferroeduardo.webcord.listener;

import com.ferroeduardo.webcord.Util;
import com.ferroeduardo.webcord.entity.GuildInfo;
import com.ferroeduardo.webcord.exception.AlreadyExistsException;
import com.ferroeduardo.webcord.service.GuildInfoService;
import net.dv8tion.jda.api.EmbedBuilder;
import net.dv8tion.jda.api.MessageBuilder;
import net.dv8tion.jda.api.Permission;
import net.dv8tion.jda.api.entities.*;
import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent;
import net.dv8tion.jda.api.events.message.MessageReceivedEvent;
import net.dv8tion.jda.api.hooks.ListenerAdapter;
import net.dv8tion.jda.api.interactions.components.ItemComponent;
import net.dv8tion.jda.api.interactions.components.buttons.Button;
import net.dv8tion.jda.api.requests.restaction.MessageAction;
import net.dv8tion.jda.api.requests.restaction.interactions.ReplyCallbackAction;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import javax.persistence.NoResultException;
import java.awt.*;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.random.RandomGenerator;

import static com.ferroeduardo.webcord.listener.DefaultMessages.*;

public class MessageListener extends ListenerAdapter {

    public static final String COMMAND_PREFIX = "\\";
    private static final Logger LOGGER = LogManager.getLogger(MessageListener.class);
    private Map<String, WebObserver> webObservers;
    private Map<String, String> infos;
    private RandomGenerator randomGenerator;
    private GuildInfoService guildInfoService;

    public MessageListener(GuildInfoService guildInfoService, @Nullable Map<String, String> infos) {
        LOGGER.info("Iniciando MessageListener");
        this.guildInfoService = guildInfoService;
        this.randomGenerator =  RandomGenerator.getDefault();
        this.infos = infos;
    }

    public void setWebObservers(Map<String, WebObserver> webObservers) {
        this.webObservers = webObservers;
    }

    @Override
    public void onMessageReceived(@Nonnull MessageReceivedEvent event) {
        if (event.getAuthor().isBot()) {
            return;
        }
        final Message msg = event.getMessage();
        final ChannelType channelType = event.getChannelType();
        final MessageChannel messageChannel = event.getChannel();
        final User author = event.getAuthor();
        final String content = msg.getContentRaw();

        final Consumer<Message> deleteMessagesAfterTime = message -> {
            message.delete().queueAfter(5, TimeUnit.SECONDS);
            msg.delete().queueAfter(5, TimeUnit.SECONDS);
        };

        if (content.equals(COMMAND_PREFIX + "ping")) {
            long time = System.currentTimeMillis();
            messageChannel.sendMessage("Pong!")
                    .queue(response -> {
                        response.editMessageFormat("Pong: %d ms", System.currentTimeMillis() - time).queue();
                    });
        } else if (content.equals(COMMAND_PREFIX + "help")) {
            StringBuilder descriptionStringBuilder = getHelpStringBuilder();
            EmbedBuilder eb = new EmbedBuilder();
            eb.setTimestamp(event.getMessage().getTimeCreated());
            eb.setColor(new Color((int) (randomGenerator.nextDouble() * 0x1000000)));
            eb.setTitle("Ajuda");
            eb.setDescription(descriptionStringBuilder.toString());
            MessageAction messageAction = msg.replyEmbeds(eb.build());
            if (this.infos != null && !this.infos.isEmpty()) {
                List<ItemComponent> componentList = new ArrayList<>(this.infos.size());
                this.infos.forEach((key, value) -> {
                    componentList.add(Button.link(value, key));
                });
                messageAction.setActionRow(componentList);
            }
            messageAction.queue();
        } else if (content.equals(COMMAND_PREFIX + "invite")) {
            msg.reply("Convite: " + Util.getInviteLink(event.getJDA())).queue();
        } else if (content.equals(COMMAND_PREFIX + "status")) {
            if (webObservers == null) {
                msg.reply(WAIT_A_MOMENT.getMessage()).queue(deleteMessagesAfterTime);
            } else {
                if (webObservers.isEmpty()) {
                    msg.reply(NO_WEBSITE_REGISTERED.getMessage()).queue();
                } else {
                    MessageBuilder mb = getWebObserversStatusMessageBuilder(event.getMessage().getTimeCreated());
                    msg.reply(mb.build()).queue();
                }
            }
        }

        if (channelType == ChannelType.TEXT) {
            TextChannel textChannel = event.getTextChannel();
            Member member = event.getMember();
            Guild guild = event.getGuild();
            boolean isAdmin = member.getRoles().stream().anyMatch(role -> role.hasPermission(Permission.ADMINISTRATOR));
            if (content.equals(COMMAND_PREFIX + "add")) {
                if (isAdmin) {
                    long guildId = guild.getIdLong();
                    long channelId = textChannel.getIdLong();
                    String message = addChannelToTheDatabase(guildId, channelId);
                    msg.reply(message).queue(deleteMessagesAfterTime);
                } else {
                    msg.reply(USER_IS_NOT_ADMIN.getMessage()).queue(deleteMessagesAfterTime);
                }
            } else if (content.equals(COMMAND_PREFIX + "remove")) {
                if (isAdmin) {
                    long guildId = guild.getIdLong();
                    long channelId = textChannel.getIdLong();
                    String message = removeChannelFromDatabase(guildId, channelId);
                    msg.reply(message).queue(deleteMessagesAfterTime);
                } else {
                    msg.reply(USER_IS_NOT_ADMIN.getMessage()).queue(deleteMessagesAfterTime);
                }
            }
        }
    }

    private StringBuilder getHelpStringBuilder() {
        StringBuilder descriptionStringBuilder = new StringBuilder();
        descriptionStringBuilder.append(String.format(
                """
                        Quando fico:
                        Online - Tudo funcionando perfeitamente
                        Ocupado - Algum serviço está fora do ar
                        Ausente - Inicializando bot
                        Invisível - Estou fora do ar
                                        
                        %1$sping - Ping
                        %1$shelp - Comandos
                        %1$sinvite - Convite do bot
                        %1$sstatus - Estado atual dos sites cadastrados
                                        
                        Somente servidores---------------------------------------
                        %1$sadd - Adiciona canal atual para receber avisos
                        %1$sremove - Remove canal atual e deixa de receber avisos
                        """, COMMAND_PREFIX));
        if (!webObservers.isEmpty()) {
            descriptionStringBuilder.append("\nSites cadastrados----------------------------------------\n");
            webObservers.keySet().forEach(key -> {
                descriptionStringBuilder.append(String.format("%s\n", key));
            });
        } else {
            descriptionStringBuilder.append(
                    """
                            Sites cadastrados----------------------------------------
                            Nenhum site foi cadastrado no bot
                            """);
        }
        if (this.infos != null && !this.infos.isEmpty()) {
            descriptionStringBuilder.append("------------------------------\n");
            this.infos.forEach((key, value) -> descriptionStringBuilder.append(String.format("%s: %s\n", key, value)));
        }
        return descriptionStringBuilder;
    }

    @Override
    public void onSlashCommandInteraction(@Nonnull SlashCommandInteractionEvent event) {
        String eventName = event.getName();
        if (eventName.equals("help")) {
            StringBuilder descriptionStringBuilder = getHelpStringBuilder();
            EmbedBuilder eb = new EmbedBuilder();
            eb.setTimestamp(event.getTimeCreated());
            eb.setColor(new Color((int) (randomGenerator.nextDouble() * 0x1000000)));
            eb.setTitle("Ajuda");
            eb.setDescription(descriptionStringBuilder.toString());
            MessageBuilder mb = new MessageBuilder(eb);
            ReplyCallbackAction replyAction = event.reply(mb.build());
            if (this.infos != null && !this.infos.isEmpty()) {
                List<ItemComponent> componentList = new ArrayList<>(this.infos.size());
                this.infos.forEach((key, value) -> {
                    componentList.add(Button.link(value, key));
                });
                replyAction.addActionRow(componentList);
            }
            replyAction.queue();
        } else if (eventName.equals("ping")) {
            long time = System.currentTimeMillis();
            event.reply("Pong!")
                    .queue(response -> {
                        response.editOriginal(String.format("Pong: %d ms", System.currentTimeMillis() - time)).queue();
                    });
        } else if (eventName.equals("invite")) {
            event.reply(Util.getInviteLink(event.getJDA())).queue();
        } else if (eventName.equals("status")) {
            if (webObservers == null) {
                event.reply(WAIT_A_MOMENT.getMessage()).queue();
            } else {
                if (webObservers.isEmpty()) {
                    event.reply(NO_WEBSITE_REGISTERED.getMessage()).queue();
                } else {
                    MessageBuilder mb = getWebObserversStatusMessageBuilder(event.getTimeCreated());
                    event.reply(mb.build()).queue();
                }
            }
        }
        if (event.getChannelType() == ChannelType.TEXT) {
            if (eventName.equals("add")) {
                Member member = event.getMember();
                boolean isAdmin = member.getRoles().stream().anyMatch(role -> role.hasPermission(Permission.ADMINISTRATOR));
                if (isAdmin) {
                    long channelId = event.getChannel().getIdLong();
                    long guildId = event.getGuild().getIdLong();
                    String message = addChannelToTheDatabase(guildId, channelId);
                    event.reply(message).queue();
                } else {
                    event.reply(USER_IS_NOT_ADMIN.getMessage()).queue();
                }
            } else if (eventName.equals("remove")) {
                Member member = event.getMember();
                boolean isAdmin = member.getRoles().stream().anyMatch(role -> role.hasPermission(Permission.ADMINISTRATOR));
                if (isAdmin) {
                    long channelId = event.getChannel().getIdLong();
                    long guildId = event.getGuild().getIdLong();
                    String message = removeChannelFromDatabase(guildId, channelId);
                    event.reply(message).queue();
                } else {
                    event.reply(USER_IS_NOT_ADMIN.getMessage()).queue();
                }
            }
        } else {
            event.reply("Esse comando só está disponível em canais de servidores").queue();
        }
    }

    private MessageBuilder getWebObserversStatusMessageBuilder(@Nullable OffsetDateTime timeCreated) {
        StringBuilder websiteStatusStringBuilder = getWebObserversStatusStringBuilder();
        EmbedBuilder eb = new EmbedBuilder();
        eb.setTimestamp(timeCreated);
        eb.setColor(new Color((int) (randomGenerator.nextDouble() * 0x1000000)));
        eb.setTitle("Site - Status");
        eb.setDescription(websiteStatusStringBuilder.toString());
        return new MessageBuilder(eb);
    }

    private StringBuilder getWebObserversStatusStringBuilder() {
        StringBuilder websiteStatusStringBuilder = new StringBuilder();
        webObservers.forEach((name, webObserver) -> {
            WebsiteStatus currentWebsiteStatus = webObserver.getCurrentWebsiteStatus();
            if (currentWebsiteStatus != WebsiteStatus.NONE) {
                LocalDateTime latestStatusTime = webObserver.getLatestStatusTime();
                DateTimeFormatter formatter = DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm");
                if (currentWebsiteStatus == WebsiteStatus.TIMEOUT) {
                    websiteStatusStringBuilder.append(String.format(
                            """
                                    %s:
                                    - Status: %s
                                    - Quantidade de Timeouts: %d
                                    - Desde: %s
                                                                        
                                    """,
                            name, currentWebsiteStatus.name(), webObserver.getTimeoutCount(), latestStatusTime.format(formatter)));
                } else {
                    websiteStatusStringBuilder.append(String.format(
                            """                            
                                    %s:
                                    - Status: %s
                                                                        
                                    """,
                            name, currentWebsiteStatus.name()));
                }
            } else {
                websiteStatusStringBuilder.append(String.format(
                        """
                                %s:
                                - Status: AGUARDE
                                                                
                                """,
                        name));
            }
        });
        return websiteStatusStringBuilder;
    }

    private String addChannelToTheDatabase(long guildId, long channelId) {
        String message;
        try {
            GuildInfo guildInfo = guildInfoService.find(guildId, channelId);
            if (guildInfo != null) {
                throw LOGGER.throwing(new AlreadyExistsException(ALREADY_ADDED_CHANNEL.getMessage()));
            }
            message = "Era pra essa mensagem ser impossível de aparecer. Entre em contato com o desenvolvedor ou administrador";
        } catch (NoResultException e) {
            LOGGER.trace(String.format("Nenhum canal foi encontrado, cadastrando canal '%d' do servidor '%d' no banco de dados", channelId, guildId), e);
            guildInfoService.save(new GuildInfo(guildId, channelId));
            message = CONFIGURED_WITH_SUCCESS.getMessage();
        } catch (AlreadyExistsException e) {
            LOGGER.debug(e);
            message = e.getMessage();
        } catch (Exception e) {
            LOGGER.warn(e);
            message = FAILED_TO_REMOVE_CHANNEL.getMessage();
        }
        return message;
    }

    private String removeChannelFromDatabase(long guildId, long channelId) {
        String message;
        try {
            guildInfoService.delete(guildId, channelId);
            message = CHANNEL_REMOVED_WITH_SUCCESS.getMessage();
        } catch (NoResultException e) {
            message = CHANNEL_NOT_REGISTERED_TO_BE_REMOVED.getMessage();
            LOGGER.debug(message, e);
        } catch (Exception e) {
            message = FAILED_TO_REMOVE_CHANNEL.getMessage();
            LOGGER.warn(message, e);
        }
        return message;
    }
}
