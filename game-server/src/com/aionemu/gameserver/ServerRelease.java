package com.aionemu.gameserver;

/**
 * The version this server was released as, {@code v0.1.0-beta.1} for a test patch, or {@link #DEV} for a build that was not made as a release.
 * <p>
 * The build writes it into the jar's manifest as {@code Implementation-Version}, from the {@code aion.release} property of the root pom, which the
 * release script sets; a build by {@code deploy.ps1}, and a run from the IDE (classes in a folder, no manifest), have none.
 */
public final class ServerRelease {

	/** What a build that was not made as a release says. */
	public static final String DEV = "dev";

	private static final String VERSION = read(GameServer.class.getPackage().getImplementationVersion());

	private ServerRelease() {
	}

	public static String version() {
		return VERSION;
	}

	static String read(String implementationVersion) {
		return implementationVersion == null || implementationVersion.isBlank() ? DEV : implementationVersion.strip();
	}
}
