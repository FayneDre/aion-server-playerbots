/*
 * DB changes since a31c1df (29.09.2026)
 */

UPDATE `player_skills` SET `skill_level` = 1 WHERE (`skill_id` < 30000 OR `skill_id` = 40009);

ALTER TABLE `players`
	ADD COLUMN `pvp_exp` bigint NOT NULL DEFAULT '0' AFTER `reposte_energy`;

ALTER TABLE `player_recipes`
	ADD COLUMN `production_limit` tinyint NOT NULL DEFAULT '0' AFTER `recipe_id`;

ALTER TABLE `player_appearance`
	RENAME COLUMN `jaw_height` TO `chin_height`,
	RENAME COLUMN `chin_jut` TO `cheek_bones`;

ALTER TABLE `house_scripts`
	ADD COLUMN `script_data` blob NULL AFTER `script_id`,
	ADD COLUMN `uncompressed_size` int NOT NULL DEFAULT '0' AFTER `script_data`;
UPDATE `house_scripts` SET
	`uncompressed_size` = LENGTH(CONVERT(`script` USING utf16le)),
	`script_data` = SUBSTRING(COMPRESS(CAST(CONVERT(`script` USING utf16le) AS BINARY)), 5);
ALTER TABLE `house_scripts`
	DROP COLUMN `script`,
	CHANGE COLUMN `script_data` `script` blob NOT NULL,
	ALTER COLUMN `uncompressed_size` DROP DEFAULT;

CREATE TABLE `playerbot_characters` (
	`player_id` int NOT NULL,
	`resident` tinyint NOT NULL DEFAULT '0',
	`in_world` tinyint NOT NULL DEFAULT '0',
	PRIMARY KEY (`player_id`),
	CONSTRAINT `playerbot_characters_ibfk_1` FOREIGN KEY (`player_id`) REFERENCES `players` (`id`) ON DELETE CASCADE ON UPDATE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

ALTER TABLE `playerbot_characters`
	ADD COLUMN `home_x` float NOT NULL DEFAULT '0' AFTER `in_world`,
	ADD COLUMN `home_y` float NOT NULL DEFAULT '0' AFTER `home_x`,
	ADD COLUMN `home_z` float NOT NULL DEFAULT '0' AFTER `home_y`;

ALTER TABLE `playerbot_characters`
	ADD COLUMN `visiting_from` int NOT NULL DEFAULT '0' AFTER `home_z`;
