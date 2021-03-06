package it.cavallium.dbengine.database.disk;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufAllocator;
import it.cavallium.dbengine.database.LLRange;
import java.util.Map;
import java.util.Map.Entry;
import org.rocksdb.ColumnFamilyHandle;
import org.rocksdb.ReadOptions;
import org.rocksdb.RocksDB;

public class LLLocalEntryReactiveRocksIterator extends LLLocalReactiveRocksIterator<Entry<ByteBuf, ByteBuf>> {

	public LLLocalEntryReactiveRocksIterator(RocksDB db,
			ByteBufAllocator alloc,
			ColumnFamilyHandle cfh,
			LLRange range,
			boolean allowNettyDirect,
			ReadOptions readOptions,
			String debugName) {
		super(db, alloc, cfh, range, allowNettyDirect, readOptions, true, debugName);
	}

	@Override
	public Entry<ByteBuf, ByteBuf> getEntry(ByteBuf key, ByteBuf value) {
		return Map.entry(key, value);
	}
}
