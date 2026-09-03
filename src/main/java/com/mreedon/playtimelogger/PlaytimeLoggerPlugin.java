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
import java.util.concurrent.ScheduledExecutorService;
import javax.inject.Inject;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.GameState;
import net.runelite.api.events.GameStateChanged;
import net.runelite.client.RuneLite;
import net.runelite.client.eventbus.Subscribe;
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
	private static final String CSV_HEADER = "login,logout,duration_seconds" + System.lineSeparator();

	@Inject
	private ScheduledExecutorService executor;

	private Instant sessionStart;

	@Override
	protected void startUp()
	{
		sessionStart = null;
	}

	@Override
	protected void shutDown()
	{
		// The client is closing while logged in; there will be no further
		// GameStateChanged event, so close out the open session here.
		closeSession(Instant.now());
	}

	@Subscribe
	public void onGameStateChanged(GameStateChanged event)
	{
		GameState state = event.getGameState();

		if (state == GameState.LOGGED_IN && sessionStart == null)
		{
			// Guarded on sessionStart == null so a world hop (which also
			// passes through LOGGED_IN) doesn't split one play session in two.
			sessionStart = Instant.now();
		}
		else if (state == GameState.LOGIN_SCREEN && sessionStart != null)
		{
			closeSession(Instant.now());
		}
	}

	private void closeSession(Instant end)
	{
		if (sessionStart == null)
		{
			return;
		}

		Instant start = sessionStart;
		sessionStart = null;

		Duration played = Duration.between(start, end);
		if (played.isNegative() || played.isZero())
		{
			return;
		}

		executor.execute(() -> writeSession(start, end, played));
	}

	private void writeSession(Instant start, Instant end, Duration played)
	{
		String line = start.truncatedTo(ChronoUnit.SECONDS) + "," +
			end.truncatedTo(ChronoUnit.SECONDS) + "," +
			played.getSeconds() + System.lineSeparator();

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
