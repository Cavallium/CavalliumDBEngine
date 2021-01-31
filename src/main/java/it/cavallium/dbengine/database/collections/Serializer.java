package it.cavallium.dbengine.database.collections;

import io.netty.buffer.ByteBuf;

public interface Serializer<B> {

	B deserialize(ByteBuf serialized);

	void serialize(B deserialized, ByteBuf output);

	static Serializer<ByteBuf> noop() {
		return new Serializer<>() {
			@Override
			public ByteBuf deserialize(ByteBuf serialized) {
				return serialized.readSlice(serialized.readableBytes());
			}

			@Override
			public void serialize(ByteBuf deserialized, ByteBuf output) {
				deserialized.resetReaderIndex();
				output.writeBytes(deserialized, deserialized.readableBytes());
			}
		};
	}

	static Serializer<byte[]> noopBytes() {
		return new Serializer<>() {
			@Override
			public byte[] deserialize(ByteBuf serialized) {
				var result = new byte[serialized.readableBytes()];
				serialized.readBytes(result);
				return result;
			}

			@Override
			public void serialize(byte[] deserialized, ByteBuf output) {
				output.writeBytes(deserialized);
			}
		};
	}
}
