UPDATE items SET shard = CRC32(token) % 16
