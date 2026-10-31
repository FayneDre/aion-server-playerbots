package com.aionemu.gameserver.playerbot.economy;

import java.util.EnumMap;
import java.util.Map;
import java.util.Random;
import java.util.function.Predicate;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The arithmetic of the house's targets: weights read from a property, split into whole numbers of listings, compared with what the broker holds, and
 * drawn from. Pure, so that the shares can be tested without a server.
 */
public final class BrokerHouseShares {

	private static final Logger log = LoggerFactory.getLogger(BrokerHouseShares.class);

	private BrokerHouseShares() {
	}

	/**
	 * Reads {@code NAME:weight} pairs separated by semicolons. A name the enum does not know, or a weight that is not a number or is negative, is
	 * logged and left out rather than failing the start.
	 */
	public static <K extends Enum<K>> Map<K, Integer> parse(String text, Class<K> keys) {
		Map<K, Integer> weights = new EnumMap<>(keys);
		if (text == null)
			return weights;
		for (String pair : text.split(";")) {
			if (pair.isBlank())
				continue;
			String[] parts = pair.split(":");
			try {
				int weight = Integer.parseInt(parts[1].trim());
				if (weight < 0)
					throw new IllegalArgumentException("negative weight");
				weights.put(Enum.valueOf(keys, parts[0].trim().toUpperCase()), weight);
			} catch (RuntimeException e) {
				log.warn("Ignoring the share '{}' of the broker house: {}", pair.trim(), e.toString());
			}
		}
		return weights;
	}

	/**
	 * Splits a total into whole numbers in proportion to the weights, by the largest remainder so that the parts add up to the total exactly (when any
	 * weight is positive).
	 */
	public static <K extends Enum<K>> Map<K, Integer> split(int total, Map<K, Integer> weights, Class<K> keys) {
		Map<K, Integer> parts = new EnumMap<>(keys);
		long sum = weights.values().stream().mapToLong(Integer::longValue).sum();
		if (sum <= 0 || total <= 0)
			return parts;
		Map<K, Double> remainders = new EnumMap<>(keys);
		int given = 0;
		for (Map.Entry<K, Integer> weight : weights.entrySet()) {
			double exact = (double) total * weight.getValue() / sum;
			int whole = (int) exact;
			parts.put(weight.getKey(), whole);
			remainders.put(weight.getKey(), exact - whole);
			given += whole;
		}
		while (given < total) {
			K best = null;
			for (Map.Entry<K, Double> remainder : remainders.entrySet())
				if (best == null || remainder.getValue() > remainders.get(best))
					best = remainder.getKey();
			parts.merge(best, 1, Integer::sum);
			remainders.put(best, -1.0);
			given++;
		}
		return parts;
	}

	/** @return For each key, how many listings it lacks of its target (never negative); keys already at or over their target are left out. */
	public static <K extends Enum<K>> Map<K, Integer> missing(Map<K, Integer> targets, Map<K, Integer> held, Class<K> keys) {
		Map<K, Integer> lacking = new EnumMap<>(keys);
		for (Map.Entry<K, Integer> target : targets.entrySet()) {
			int lack = target.getValue() - held.getOrDefault(target.getKey(), 0);
			if (lack > 0)
				lacking.put(target.getKey(), lack);
		}
		return lacking;
	}

	/** @return A key drawn in proportion to the weights, among the keys {@code allowed} accepts only; null if none is allowed or none has weight. */
	public static <K extends Enum<K>> K draw(Map<K, Integer> weights, Predicate<K> allowed, Random random) {
		long sum = 0;
		for (Map.Entry<K, Integer> weight : weights.entrySet())
			if (allowed.test(weight.getKey()))
				sum += weight.getValue();
		if (sum <= 0)
			return null;
		long pick = (long) (random.nextDouble() * sum);
		for (Map.Entry<K, Integer> weight : weights.entrySet()) {
			if (!allowed.test(weight.getKey()))
				continue;
			pick -= weight.getValue();
			if (pick < 0)
				return weight.getKey();
		}
		return null;
	}
}
