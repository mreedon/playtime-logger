/*
 * Copyright (c) 2026, mreedon
 * All rights reserved.
 *
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the following conditions are met:
 *
 * 1. Redistributions of source code must retain the above copyright notice, this
 *    list of conditions and the following disclaimer.
 * 2. Redistributions in binary form must reproduce the above copyright notice,
 *    this list of conditions and the following disclaimer in the documentation
 *    and/or other materials provided with the distribution.
 *
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS" AND
 * ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE IMPLIED
 * WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE ARE
 * DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT OWNER OR CONTRIBUTORS BE LIABLE FOR
 * ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL DAMAGES
 * (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES;
 * LOSS OF USE, DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND
 * ON ANY THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT
 * (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE OF THIS
 * SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
 */
package com.mreedon.playtimelogger;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Future;
import java.util.concurrent.ScheduledExecutorService;
import java.util.stream.Collectors;
import javax.inject.Inject;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Client;
import net.runelite.api.GameState;
import net.runelite.api.Player;
import net.runelite.api.events.GameStateChanged;
import net.runelite.client.RuneLite;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.events.ClientShutdown;
import net.runelite.client.plugins.Plugin;
import net.runelite.client.plugins.PluginDescriptor;

@Slf4j
@PluginDescriptor(
	name = "Playtime Logger",
	description = "Logs real login/logout session times to a local file"
)
public class PlaytimeLoggerPlugin extends Plugin
{
	private static final Path LOG_DIR = RuneLite.RUNELITE_DIR.toPath().resolve("playtime-logger");
	private static final Path LOG_FILE = LOG_DIR.resolve("sessions.csv");
	private static final String CSV_HEADER = "login,logout,duration_seconds,hops,worlds,player" + System.lineSeparator();

	@Inject
	private Client client;

	@Inject
	private ScheduledExecutorService executor;

	private Instant sessionStart;
	private int hopCount;
	private List<Integer> worlds;

	@Override
	protected void startUp()
	{
		sessionStart = null;
		hopCount = 0;
		worlds = new ArrayList<>();
	}

	@Override
	protected void shutDown()
	{
		// Fires when this plugin is individually disabled while the client
		// keeps running. Fire-and-forget is fine here; the shared executor
		// isn't going anywhere.
		closeSession(Instant.now());
	}

	@Subscribe
	public void onClientShutdown(ClientShutdown event)
	{
		// Fires when the whole client is closing. shutDown() above is NOT
		// called in this case -- PluginManager.stopPlugin() is only invoked
		// when a plugin is toggled off, not on client exit. The process is
		// about to exit, so the write must be registered with the event via
		// waitFor() or it may never happen; RuneLite gives registered
		// futures up to 10s to finish before it force-exits.
		Future<?> pendingWrite = closeSession(Instant.now());
		if (pendingWrite != null)
		{
			event.waitFor(pendingWrite);
		}
	}

	@Subscribe
	public void onGameStateChanged(GameStateChanged event)
	{
		GameState state = event.getGameState();

		if (state == GameState.LOGGED_IN)
		{
			// Guarded on sessionStart == null so a world hop (which also
			// passes through LOGGED_IN) doesn't split one play session in two.
			if (sessionStart == null)
			{
				sessionStart = Instant.now();
				hopCount = 0;
				worlds = new ArrayList<>();
			}
			worlds.add(client.getWorld());
		}
		else if (state == GameState.HOPPING && sessionStart != null)
		{
			hopCount++;
		}
		else if (state == GameState.LOGIN_SCREEN && sessionStart != null)
		{
			closeSession(Instant.now());
		}
	}

	private Future<?> closeSession(Instant end)
	{
		if (sessionStart == null)
		{
			return null;
		}

		Instant start = sessionStart;
		int hops = hopCount;
		List<Integer> sessionWorlds = worlds;
		String player = playerName();
		sessionStart = null;
		hopCount = 0;
		worlds = new ArrayList<>();

		Duration played = Duration.between(start, end);
		if (played.isNegative() || played.isZero())
		{
			return null;
		}

		return executor.submit(() -> writeSession(start, end, played, hops, sessionWorlds, player));
	}

	private String playerName()
	{
		// The player can only be logged out here if they were logged in a
		// moment ago, so this should always be non-null in practice --
		// checked anyway since a null local player is a known possibility
		// elsewhere in the client during state transitions.
		Player localPlayer = client.getLocalPlayer();
		if (localPlayer == null || localPlayer.getName() == null)
		{
			return "unknown";
		}
		return localPlayer.getName();
	}

	private void writeSession(Instant start, Instant end, Duration played, int hops, List<Integer> sessionWorlds, String player)
	{
		String worldList = sessionWorlds.stream()
			.map(String::valueOf)
			.collect(Collectors.joining(";"));

		String line = start.truncatedTo(ChronoUnit.SECONDS) + "," +
			end.truncatedTo(ChronoUnit.SECONDS) + "," +
			played.getSeconds() + "," +
			hops + "," +
			worldList + "," +
			player + System.lineSeparator();

		try
		{
			Files.createDirectories(LOG_DIR);

			boolean isNewFile = !Files.exists(LOG_FILE);
			if (isNewFile)
			{
				Files.write(LOG_FILE, CSV_HEADER.getBytes(StandardCharsets.UTF_8),
					StandardOpenOption.CREATE, StandardOpenOption.APPEND);
			}

			Files.write(LOG_FILE, line.getBytes(StandardCharsets.UTF_8),
				StandardOpenOption.CREATE, StandardOpenOption.APPEND);
		}
		catch (IOException e)
		{
			log.warn("Failed to write playtime session", e);
		}
	}
}
