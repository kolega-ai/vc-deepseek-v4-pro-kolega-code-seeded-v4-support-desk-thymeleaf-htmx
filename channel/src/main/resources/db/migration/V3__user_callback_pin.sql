-- Reusable caller-verification pin for the phone support channel.
ALTER TABLE users ADD COLUMN callback_pin_hash varchar(64);
