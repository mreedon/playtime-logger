package com.mreedon.playtimelogger;

import net.runelite.client.RuneLite;
import net.runelite.client.externalplugins.ExternalPluginManager;

public class PlaytimeLoggerPluginTest
{
	public static void main(String[] args) throws Exception
	{
		ExternalPluginManager.loadBuiltin(PlaytimeLoggerPlugin.class);
		RuneLite.main(args);
	}
}
