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
	/**
	 * The shortest and tallest a bot may be, drawn between the two.
	 * <p>
	 * Bounds rather than a middle and a spread, because the two ends are the thing worth being sure of: the question is never "how much may this
	 * vary" but "how small is too small". A character made in the creation screen came out at 1.0986, so an assumed neutral of 1.0 made every bot
	 * shorter than any real player and the smallest of them a dwarf. These sit either side of that known good value, far enough apart to tell people
	 * apart across a village and no further, since where the game stops accepting a height is not documented anywhere.
	 */
	private static final float SHORTEST = 1.04f, TALLEST = 1.16f;
	/** How far a face or body slider may move from what it was, and how far from neutral it may ever end up. */
	private static final int FEATURE_NUDGE = 30, FEATURE_LIMIT = 90;
	/** How far skin and lips may be shaded. Small: skin is the one colour whose every wrong value is somebody from another planet. */
	private static final float SKIN_SHADING = 0.12f;
	/** Hair and eyes take a wider shading, since light and dark hair are both ordinary. */
	private static final float HAIR_SHADING = 0.35f;
	/**
	 * Complexions to draw from, as red, green and blue.
	 * <p>
	 * Several rather than one, because a population shaded from a single colour is one person at different exposures. These are ordinary human
	 * ranges, pale to dark, and {@link #shade} moves each one a little further.
	 */
	private static final int[][] COMPLEXIONS = { { 238, 198, 193 }, { 232, 200, 178 }, { 214, 176, 150 }, { 190, 150, 124 }, { 150, 112, 88 } };
	/** Hair colours: black, dark and light brown, auburn, blond, grey. */
	private static final int[][] HAIR_COLOURS = { { 30, 26, 28 }, { 51, 34, 24 }, { 101, 67, 33 }, { 128, 64, 32 }, { 200, 170, 110 },
		{ 160, 160, 165 } };
	private static final int[] PLAIN_LIPS = { 192, 136, 128 }, PLAIN_EYES = { 74, 56, 40 };

	private BotAppearance() {
	}

	/** @return A plain character of the given gender, varied as {@link #vary} varies a copied one. */
	public static PlayerAppearance invent() {
		PlayerAppearance appearance = new PlayerAppearance();
		appearance.setSkinRGB(stored(COMPLEXIONS[RANDOM.nextInt(COMPLEXIONS.length)]));
		appearance.setHairRGB(stored(HAIR_COLOURS[RANDOM.nextInt(HAIR_COLOURS.length)]));
		appearance.setLipRGB(stored(PLAIN_LIPS));
		appearance.setEyeRGB(stored(PLAIN_EYES));
		appearance.setHeight(SHORTEST);
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

		appearance.setHeight(SHORTEST + RANDOM.nextFloat() * (TALLEST - SHORTEST));
		return appearance;
	}

	/**
	 * Packs a colour the way the client stores it, which is blue first.
	 * <p>
	 * Worth stating rather than assuming, because assuming it was red first is what turned a village into smurfs: a perfectly good complexion of
	 * 224, 192, 168 written the wrong way round is read as a pale blue, and nothing in the code looks wrong. The character this was all copied from
	 * settles it — its stored skin is C1C6EE, which is a bluish colour read one way and an ordinary rosy one read the other.
	 *
	 * @param rgb Red, green and blue, in that order, as anyone would say them.
	 */
	private static int stored(int[] rgb) {
		return rgb[2] << 16 | rgb[1] << 8 | rgb[0];
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
