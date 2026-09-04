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
import net.runelite.client.callback.ClientThread;
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
	private ClientThread clientThread;

	@Inject
	private ScheduledExecutorService executor;

	private Instant sessionStart;
	private int hopCount;
	private List<Integer> worlds;
	private String playerName;

	@Override
	protected void startUp()
	{
		hopCount = 0;
		worlds = new ArrayList<>();
		playerName = null;

		if (client.getGameState() == GameState.LOGGED_IN)
		{
			// The plugin can (re)start while already logged in -- e.g.
			// toggled off and back on. There's no earlier LOGGED_IN
			// transition left to catch in that case, so treat "now" as the
			// session start rather than silently dropping the remainder of
			// an already-open session until the next real login.
			sessionStart = Instant.now();
			worlds.add(client.getWorld());
			capturePlayerName();
		}
		else
		{
			sessionStart = null;
		}
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
				playerName = null;
				capturePlayerName();
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

	private void capturePlayerName()
	{
		// getLocalPlayer() is unset for a moment around login, and observed
		// to already be cleared again by the time LOGIN_SCREEN fires on
		// logout -- there's no single state-change event where it's
		// reliably available. invokeLater retries on the client thread
		// until the supplier returns true, which is the standard way to
		// wait out that gap; captured once per session and cached, since
		// the account can't change mid-session.
		clientThread.invokeLater(() ->
		{
			if (client.getGameState().getState() < GameState.LOGIN_SCREEN.getState())
			{
				return false;
			}
			Player localPlayer = client.getLocalPlayer();
			if (localPlayer == null)
			{
				return false;
			}
			if (localPlayer.getName() != null)
			{
				playerName = localPlayer.getName();
				return true;
			}
			return false;
		});
	}

	// onGameStateChanged runs on the client thread; shutDown() and
	// onClientShutdown() both run on the EDT. Without this, a real logout
	// landing at the same instant as a plugin disable/client close could
	// have both threads read sessionStart as non-null before either clears
	// it, logging the same session twice.
	private synchronized Future<?> closeSession(Instant end)
	{
		if (sessionStart == null)
		{
			return null;
		}

		Instant start = sessionStart;
		int hops = hopCount;
		List<Integer> sessionWorlds = worlds;
		String player = playerName == null ? "unknown" : playerName;
		sessionStart = null;
		hopCount = 0;
		worlds = new ArrayList<>();
		playerName = null;

		Duration played = Duration.between(start, end);
		if (played.isNegative() || played.isZero())
		{
			return null;
		}

		return executor.submit(() -> writeSession(start, end, played, hops, sessionWorlds, player));
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
			csvField(player) + System.lineSeparator();

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

	// OSRS display names can't legally contain a comma, quote, or newline, so
	// this never actually triggers today -- but that's an assumption owned
	// by Jagex's naming rules, not this plugin, and RFC 4180 quoting is
	// cheap enough not to lean on it.
	private static String csvField(String value)
	{
		if (value.indexOf(',') < 0 && value.indexOf('"') < 0 && value.indexOf('\n') < 0 && value.indexOf('\r') < 0)
		{
			return value;
		}
		return '"' + value.replace("\"", "\"\"") + '"';
	}
}
