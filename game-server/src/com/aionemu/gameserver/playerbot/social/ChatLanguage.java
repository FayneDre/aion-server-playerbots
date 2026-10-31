package com.aionemu.gameserver.playerbot.social;

/** The language the bots speak in the chat channels and by shout: {@code gameserver.playerbot.chat.language} in {@code playerbot.properties}. */
public enum ChatLanguage {
	FR("fr"), EN("en");

	private final String code;

	ChatLanguage(String code) {
		this.code = code;
	}

	/** @return the language as the chat files name it: {@code bot_chat/chat_<code>.txt}. */
	public String code() {
		return code;
	}
}
