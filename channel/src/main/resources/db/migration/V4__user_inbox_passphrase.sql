-- Shared passphrase that opens the support inbox console.
ALTER TABLE users ADD COLUMN inbox_passphrase_hash varchar(128);
