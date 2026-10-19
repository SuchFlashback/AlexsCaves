package com.github.alexmodguy.alexscaves.server.misc.war;

import com.github.alexmodguy.alexscaves.server.misc.war.NukeWars.Faction;
import com.github.alexmodguy.alexscaves.server.misc.war.NukeWars.ProposalKind;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.brigadier.exceptions.SimpleCommandExceptionType;
import com.mojang.brigadier.suggestion.SuggestionProvider;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.event.RegisterCommandsEvent;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.UUID;

/**
 * /guerra: wars and peace signed in game by both sides (party owners, or players without a party).
 * Peace is signed by both sides too, or either side can surrender alone.
 */
public final class NukeWarCommands {

    private static final String ARG = "objetivo";

    private NukeWarCommands() {
    }

    public static void onRegisterCommands(RegisterCommandsEvent event) {
        register(event.getDispatcher());
    }

    private static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        SuggestionProvider<CommandSourceStack> names = (context, builder) ->
                SharedSuggestionProvider.suggest(NukeWars.nameSuggestions(context.getSource().getServer()), builder);
        dispatcher.register(Commands.literal("guerra")
                .executes(context -> help(context.getSource()))
                .then(Commands.literal("proponer").then(Commands.argument(ARG, StringArgumentType.greedyString()).suggests(names)
                        .executes(context -> proposeWar(context.getSource(), StringArgumentType.getString(context, ARG)))))
                .then(Commands.literal("aceptar").then(Commands.argument(ARG, StringArgumentType.greedyString()).suggests(names)
                        .executes(context -> acceptWar(context.getSource(), StringArgumentType.getString(context, ARG)))))
                .then(Commands.literal("rechazar").then(Commands.argument(ARG, StringArgumentType.greedyString()).suggests(names)
                        .executes(context -> reject(context.getSource(), StringArgumentType.getString(context, ARG)))))
                .then(Commands.literal("paz")
                        .then(Commands.literal("proponer").then(Commands.argument(ARG, StringArgumentType.greedyString()).suggests(names)
                                .executes(context -> proposePeace(context.getSource(), StringArgumentType.getString(context, ARG)))))
                        .then(Commands.literal("aceptar").then(Commands.argument(ARG, StringArgumentType.greedyString()).suggests(names)
                                .executes(context -> acceptPeace(context.getSource(), StringArgumentType.getString(context, ARG))))))
                .then(Commands.literal("rendirse").then(Commands.argument(ARG, StringArgumentType.greedyString()).suggests(names)
                        .executes(context -> surrender(context.getSource(), StringArgumentType.getString(context, ARG)))))
                .then(Commands.literal("lista").executes(context -> list(context.getSource())))
                .then(Commands.literal("terminar").requires(source -> source.hasPermission(2))
                        .then(Commands.argument("a", StringArgumentType.string()).suggests(names)
                                .then(Commands.argument("b", StringArgumentType.string()).suggests(names)
                                        .executes(context -> forceEnd(context.getSource(), StringArgumentType.getString(context, "a"), StringArgumentType.getString(context, "b")))))));
    }

    private record Signer(ServerPlayer player, Faction faction) {
    }

    private static CommandSyntaxException fail(String message) {
        return new SimpleCommandExceptionType(Component.literal(message)).create();
    }

    private static Signer signer(CommandSourceStack source) throws CommandSyntaxException {
        ServerPlayer player = source.getPlayerOrException();
        MinecraftServer server = source.getServer();
        Faction faction = NukeWars.factionOf(server, player.getUUID());
        if (faction.party() && !player.getUUID().equals(NukeWars.signerOf(server, faction.id()))) {
            UUID owner = NukeWars.signerOf(server, faction.id());
            throw fail("Solo el dueño de tu partido «" + NukeWars.factionName(server, faction.id()) + "»"
                    + (owner == null ? "" : " (" + NukeWars.playerName(server, owner) + ")") + " puede firmar guerras y paces.");
        }
        return new Signer(player, faction);
    }

    private static Faction target(MinecraftServer server, String name, Faction self) throws CommandSyntaxException {
        Faction target = NukeWars.findFaction(server, name);
        if (target == null) {
            throw fail("No conozco a ningún jugador ni partido llamado «" + name + "».");
        }
        if (target.id().equals(self.id())) {
            throw fail("Ese es tu propio bando.");
        }
        return target;
    }

    private static MutableComponent name(MinecraftServer server, UUID faction) {
        return NukeWarText.faction(NukeWars.factionName(server, faction));
    }

    /** Sends a message to whoever signs for this faction, if they are online. Returns whether they were. */
    private static boolean notifySigner(MinecraftServer server, UUID faction, Component message) {
        UUID signer = NukeWars.signerOf(server, faction);
        ServerPlayer player = signer == null ? null : server.getPlayerList().getPlayer(signer);
        if (player != null) {
            player.sendSystemMessage(message);
            return true;
        }
        return false;
    }

    private static void broadcast(MinecraftServer server, Component message) {
        server.getPlayerList().broadcastSystemMessage(message, false);
    }

    private static int help(CommandSourceStack source) {
        source.sendSuccess(() -> Component.literal(
                "⚔ Guerra nuclear: solo un bando enemigo declarado puede bombardear tus parcelas, y solo con bombas soltadas desde un buque de guerra.\n"
                        + "La firman los dos bandos (el dueño del partido, o el jugador si no tiene partido):\n"
                        + "/guerra proponer <jugador o partido> · /guerra aceptar <…> · /guerra rechazar <…>\n"
                        + "/guerra paz proponer <…> · /guerra paz aceptar <…> · /guerra rendirse <…>\n"
                        + "/guerra lista").withStyle(ChatFormatting.GRAY), false);
        return 1;
    }

    private static int proposeWar(CommandSourceStack source, String targetName) throws CommandSyntaxException {
        MinecraftServer server = source.getServer();
        Signer me = signer(source);
        Faction target = target(server, targetName, me.faction());
        NukeWarData data = NukeWarData.get(server);
        if (data.isAtWar(me.faction().id(), target.id())) {
            throw fail("Ya estáis en guerra con «" + NukeWars.factionName(server, target.id()) + "».");
        }
        String targetSignerCommand = NukeWars.factionName(server, me.faction().id());
        if (NukeWars.hasProposal(ProposalKind.WAR, target.id(), me.faction().id())) {
            source.sendSuccess(() -> Component.literal("").append(name(server, target.id())).append(" ya te ha propuesto la guerra. ")
                    .append(NukeWarText.button("Aceptar", "/guerra aceptar " + NukeWars.factionName(server, target.id()), ChatFormatting.RED)), false);
            return 0;
        }
        NukeWars.propose(ProposalKind.WAR, me.faction().id(), target.id());
        boolean online = notifySigner(server, target.id(), Component.literal("⚔ ").withStyle(ChatFormatting.RED)
                .append(name(server, me.faction().id())).append(" propone la guerra a ").append(target.party() ? "tu partido " : "")
                .append(name(server, target.id()))
                .append(". Si aceptas, cada bando podrá bombardear las parcelas del otro con bombas nucleares soltadas desde un buque de guerra. Caduca en 5 minutos. ")
                .append(NukeWarText.button("Aceptar", "/guerra aceptar " + targetSignerCommand, ChatFormatting.RED)).append(" ")
                .append(NukeWarText.button("Rechazar", "/guerra rechazar " + targetSignerCommand, ChatFormatting.GREEN)));
        UUID targetSigner = NukeWars.signerOf(server, target.id());
        source.sendSuccess(() -> Component.literal("Has propuesto la guerra a ").append(name(server, target.id()))
                .append(". Tiene que aceptarla " + (targetSigner == null ? "su firmante" : NukeWars.playerName(server, targetSigner))
                        + " en 5 minutos" + (online ? "." : " (ahora no está conectado).")), false);
        return 1;
    }

    private static int acceptWar(CommandSourceStack source, String fromName) throws CommandSyntaxException {
        MinecraftServer server = source.getServer();
        Signer me = signer(source);
        Faction from = target(server, fromName, me.faction());
        if (!NukeWars.takeProposal(ProposalKind.WAR, from.id(), me.faction().id())) {
            throw fail("No tienes ninguna propuesta de guerra de «" + NukeWars.factionName(server, from.id()) + "» (caducan a los 5 minutos).");
        }
        NukeWarData data = NukeWarData.get(server);
        data.startWar(from.id(), me.faction().id());
        NukeWars.clearProposals(ProposalKind.PEACE, from.id(), me.faction().id());
        broadcast(server, Component.literal("⚔ ¡Guerra! ").withStyle(ChatFormatting.RED, ChatFormatting.BOLD)
                .append(Component.literal("").withStyle(ChatFormatting.RESET).append(name(server, from.id())).append(" y ").append(name(server, me.faction().id()))
                        .append(" han firmado la guerra: desde ahora cada bando puede bombardear las parcelas del otro. Se acaba con /guerra paz o /guerra rendirse.")));
        return 1;
    }

    private static int reject(CommandSourceStack source, String fromName) throws CommandSyntaxException {
        MinecraftServer server = source.getServer();
        Signer me = signer(source);
        Faction from = target(server, fromName, me.faction());
        boolean war = NukeWars.takeProposal(ProposalKind.WAR, from.id(), me.faction().id());
        boolean peace = NukeWars.takeProposal(ProposalKind.PEACE, from.id(), me.faction().id());
        if (!war && !peace) {
            throw fail("No tienes ninguna propuesta de «" + NukeWars.factionName(server, from.id()) + "».");
        }
        String what = war ? "guerra" : "paz";
        source.sendSuccess(() -> Component.literal("Has rechazado la propuesta de " + what + " de ").append(name(server, from.id())).append("."), false);
        notifySigner(server, from.id(), Component.literal("").append(name(server, me.faction().id())).append(" ha rechazado tu propuesta de " + what + "."));
        return 1;
    }

    private static int proposePeace(CommandSourceStack source, String targetName) throws CommandSyntaxException {
        MinecraftServer server = source.getServer();
        Signer me = signer(source);
        Faction target = target(server, targetName, me.faction());
        if (!NukeWarData.get(server).isAtWar(me.faction().id(), target.id())) {
            throw fail("No estáis en guerra con «" + NukeWars.factionName(server, target.id()) + "».");
        }
        if (NukeWars.hasProposal(ProposalKind.PEACE, target.id(), me.faction().id())) {
            source.sendSuccess(() -> Component.literal("").append(name(server, target.id())).append(" ya te ha propuesto la paz. ")
                    .append(NukeWarText.button("Firmar la paz", "/guerra paz aceptar " + NukeWars.factionName(server, target.id()), ChatFormatting.GREEN)), false);
            return 0;
        }
        NukeWars.propose(ProposalKind.PEACE, me.faction().id(), target.id());
        String myName = NukeWars.factionName(server, me.faction().id());
        boolean online = notifySigner(server, target.id(), Component.literal("🕊 ").withStyle(ChatFormatting.GREEN)
                .append(name(server, me.faction().id())).append(" propone la paz. Caduca en 5 minutos. ")
                .append(NukeWarText.button("Firmar la paz", "/guerra paz aceptar " + myName, ChatFormatting.GREEN)).append(" ")
                .append(NukeWarText.button("Rechazar", "/guerra rechazar " + myName, ChatFormatting.RED)));
        source.sendSuccess(() -> Component.literal("Has propuesto la paz a ").append(name(server, target.id()))
                .append(online ? ". Tiene 5 minutos para firmarla." : ". Ahora no está conectado; la propuesta caduca en 5 minutos. También puedes rendirte: /guerra rendirse."), false);
        return 1;
    }

    private static int acceptPeace(CommandSourceStack source, String fromName) throws CommandSyntaxException {
        MinecraftServer server = source.getServer();
        Signer me = signer(source);
        Faction from = target(server, fromName, me.faction());
        if (!NukeWars.takeProposal(ProposalKind.PEACE, from.id(), me.faction().id())) {
            throw fail("No tienes ninguna propuesta de paz de «" + NukeWars.factionName(server, from.id()) + "» (caducan a los 5 minutos).");
        }
        NukeWarData.get(server).endWar(from.id(), me.faction().id());
        broadcast(server, Component.literal("🕊 ").withStyle(ChatFormatting.GREEN).append(name(server, from.id())).append(" y ")
                .append(name(server, me.faction().id())).append(" han firmado la paz: sus parcelas vuelven a estar a salvo de las bombas."));
        return 1;
    }

    private static int surrender(CommandSourceStack source, String enemyName) throws CommandSyntaxException {
        MinecraftServer server = source.getServer();
        Signer me = signer(source);
        Faction enemy = target(server, enemyName, me.faction());
        if (!NukeWarData.get(server).endWar(me.faction().id(), enemy.id())) {
            throw fail("No estáis en guerra con «" + NukeWars.factionName(server, enemy.id()) + "».");
        }
        NukeWars.clearProposals(ProposalKind.PEACE, me.faction().id(), enemy.id());
        broadcast(server, Component.literal("🏳 ").withStyle(ChatFormatting.WHITE).append(name(server, me.faction().id())).append(" se ha rendido ante ")
                .append(name(server, enemy.id())).append(". Se acabó la guerra: sus parcelas vuelven a estar a salvo de las bombas."));
        return 1;
    }

    private static int list(CommandSourceStack source) {
        MinecraftServer server = source.getServer();
        SimpleDateFormat format = new SimpleDateFormat("dd/MM/yyyy");
        MutableComponent message = Component.literal("");
        List<NukeWarData.War> wars = List.copyOf(NukeWarData.get(server).getWars());
        if (wars.isEmpty()) {
            message.append(Component.literal("No hay ninguna guerra en curso.").withStyle(ChatFormatting.GRAY));
        } else {
            message.append(Component.literal("Guerras en curso:").withStyle(ChatFormatting.RED));
            for (NukeWarData.War war : wars) {
                message.append("\n⚔ ").append(name(server, war.first())).append(" contra ").append(name(server, war.second()))
                        .append(Component.literal(" (desde el " + format.format(new Date(war.sinceMillis())) + ")").withStyle(ChatFormatting.GRAY));
            }
        }
        ServerPlayer player = source.getPlayer();
        if (player != null) {
            Faction mine = NukeWars.factionOf(server, player.getUUID());
            UUID signer = NukeWars.signerOf(server, mine.id());
            message.append(Component.literal("\nTu bando: ").withStyle(ChatFormatting.GRAY)).append(name(server, mine.id()))
                    .append(Component.literal(mine.party() && signer != null ? " (firma " + NukeWars.playerName(server, signer) + ")" : "").withStyle(ChatFormatting.GRAY));
            for (NukeWars.PendingProposal proposal : NukeWars.proposalsOf(mine.id())) {
                String what = proposal.kind() == ProposalKind.WAR ? "guerra" : "paz";
                message.append(Component.literal("\n· Propuesta de " + what + (proposal.outgoing() ? " enviada a " : " recibida de ")).withStyle(ChatFormatting.GRAY))
                        .append(name(server, proposal.otherFaction()));
            }
        }
        source.sendSuccess(() -> message, false);
        return wars.size();
    }

    private static int forceEnd(CommandSourceStack source, String a, String b) throws CommandSyntaxException {
        MinecraftServer server = source.getServer();
        Faction first = NukeWars.findFaction(server, a);
        Faction second = NukeWars.findFaction(server, b);
        if (first == null || second == null) {
            throw fail("No conozco a «" + (first == null ? a : b) + "».");
        }
        if (!NukeWarData.get(server).endWar(first.id(), second.id())) {
            throw fail("Esos dos bandos no están en guerra.");
        }
        NukeWars.clearProposals(ProposalKind.PEACE, first.id(), second.id());
        broadcast(server, Component.literal("La moderación ha terminado la guerra entre ").withStyle(ChatFormatting.GRAY)
                .append(name(server, first.id())).append(" y ").append(name(server, second.id())).append("."));
        return 1;
    }
}
