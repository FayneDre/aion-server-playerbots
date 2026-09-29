package com.aionemu.gameserver.playerbot.lifecycle;

import java.util.Random;

import com.aionemu.gameserver.model.gameobjects.player.PlayerAppearance;

/**
 * Builds a face and a body for a bot.
 * <p>
 * Either from a character to copy, or from nothing at all. Copying was the only way at first, on the belief that a blank appearance is unusable — it
 * is all zeroes, and a height of zero renders as something the client cannot draw. But height is the only field where zero is meaningless: every other
 * one is a slider about a neutral middle, and zero is that middle. A blank appearance with a sensible height is therefore an ordinary, plain character,
 * which is exactly what a population needs as a starting point.
 * <p>
 * That distinction is what lets a fresh installation populate a map without anyone first creating a character by hand for the bots to be cloned from.
 */
public class BotAppearance {

	private static final Random RANDOM = new Random();
	/** How many head and hair models the creation screen offers. Outside this is a missing model, not a different face. */
	private static final int FACE_MODELS = 12, HAIR_MODELS = 12;
	/** The middle of the height slider, and how far either side of it a character may be. */
	private static final float NEUTRAL_HEIGHT = 1f, HEIGHT_SPREAD = 0.08f;
	/** How far a face or body slider may move from what it was, and how far from neutral it may ever end up. */
	private static final int FEATURE_NUDGE = 30, FEATURE_LIMIT = 90;
	/** How far skin and lips may be shaded. Small: skin is the one colour whose every wrong value is somebody from another planet. */
	private static final float SKIN_SHADING = 0.12f;
	/** Hair and eyes take a wider shading, since light and dark hair are both ordinary. */
	private static final float HAIR_SHADING = 0.35f;
	/** Plain human colouring, used when there is no character to take colouring from. */
	private static final int PLAIN_SKIN = 0xE0C0A8, PLAIN_HAIR = 0x332218, PLAIN_LIPS = 0xC08878, PLAIN_EYES = 0x4A3828;

	private BotAppearance() {
	}

	/** @return A plain character of the given gender, varied as {@link #vary} varies a copied one. */
	public static PlayerAppearance invent() {
		PlayerAppearance appearance = new PlayerAppearance();
		appearance.setSkinRGB(PLAIN_SKIN);
		appearance.setHairRGB(PLAIN_HAIR);
		appearance.setLipRGB(PLAIN_LIPS);
		appearance.setEyeRGB(PLAIN_EYES);
		appearance.setHeight(NEUTRAL_HEIGHT);
		// everything else is left at zero on purpose: zero is the middle of each slider, which is a face with no exaggeration anywhere
		return vary(appearance);
	}

	/**
	 * Makes a face somebody else's.
	 * <p>
	 * Three kinds of change, carrying different risks:
	 * <ul>
	 * <li>colours are shaded, never redrawn — every channel by the same factor, so the hue survives and only the shade changes. Drawing fresh colours
	 * gave a village of red and blue people with green hair: there are sixteen million colours and almost none is one a person comes in;</li>
	 * <li>head and hair models are picked from what the creation screen offers, since a model number that does not exist is a missing face;</li>
	 * <li>sliders are nudged, never redrawn. A character built from random ones is a gargoyle.</li>
	 * </ul>
	 * Height moves least of all: its extremes are the ones that read immediately as broken rather than as a different person.
	 */
	public static PlayerAppearance vary(PlayerAppearance appearance) {
		appearance.setHairRGB(shade(appearance.getHairRGB(), HAIR_SHADING));
		appearance.setLipRGB(shade(appearance.getLipRGB(), SKIN_SHADING));
		appearance.setSkinRGB(shade(appearance.getSkinRGB(), SKIN_SHADING));
		appearance.setEyeRGB(shade(appearance.getEyeRGB(), HAIR_SHADING));

		appearance.setFace(RANDOM.nextInt(FACE_MODELS));
		appearance.setHair(RANDOM.nextInt(HAIR_MODELS));

		appearance.setFaceShape(nudge(appearance.getFaceShape()));
		appearance.setForehead(nudge(appearance.getForehead()));
		appearance.setEyeHeight(nudge(appearance.getEyeHeight()));
		appearance.setEyeSpace(nudge(appearance.getEyeSpace()));
		appearance.setEyeSize(nudge(appearance.getEyeSize()));
		appearance.setNose(nudge(appearance.getNose()));
		appearance.setNoseWidth(nudge(appearance.getNoseWidth()));
		appearance.setCheek(nudge(appearance.getCheek()));
		appearance.setMouthSize(nudge(appearance.getMouthSize()));
		appearance.setLipSize(nudge(appearance.getLipSize()));
		appearance.setJawHeigh(nudge(appearance.getJawHeigh()));
		appearance.setChinJut(nudge(appearance.getChinJut()));
		appearance.setShoulders(nudge(appearance.getShoulders()));
		appearance.setTorso(nudge(appearance.getTorso()));
		appearance.setWaist(nudge(appearance.getWaist()));
		appearance.setArmThickness(nudge(appearance.getArmThickness()));
		appearance.setLegThickness(nudge(appearance.getLegThickness()));

		float height = appearance.getHeight() <= 0 ? NEUTRAL_HEIGHT : appearance.getHeight();
		appearance.setHeight(height * (1 + (RANDOM.nextFloat() - 0.5f) * 2 * HEIGHT_SPREAD));
		return appearance;
	}

	/**
	 * Varies a colour without inventing one. Scaling every channel by the same factor keeps the hue and changes only the shade, and it does not need
	 * to know whether the channels are stored red first or blue first: an even scaling is the same operation either way round.
	 *
	 * @param spread How far the shade may move, as a fraction: 0.25 means three quarters to five quarters of the original.
	 */
	private static int shade(int rgb, float spread) {
		float factor = 1 + (RANDOM.nextFloat() - 0.5f) * 2 * spread;
		return channel((rgb >> 16) & 0xFF, factor) << 16 | channel((rgb >> 8) & 0xFF, factor) << 8 | channel(rgb & 0xFF, factor);
	}

	private static int channel(int value, float factor) {
		return Math.clamp(Math.round(value * factor), 0, 255);
	}

	/**
	 * Moves one slider a little.
	 *
	 * @param value The stored value: a signed byte kept as an unsigned int, so 253 means -3 and not "almost the maximum".
	 * @return The nudged value in the same form, kept well inside the range so no feature reaches the extreme the sliders allow.
	 */
	private static int nudge(int value) {
		int moved = Math.clamp((byte) value + RANDOM.nextInt(FEATURE_NUDGE * 2 + 1) - FEATURE_NUDGE, -FEATURE_LIMIT, FEATURE_LIMIT);
		return moved & 0xFF;
	}
}
