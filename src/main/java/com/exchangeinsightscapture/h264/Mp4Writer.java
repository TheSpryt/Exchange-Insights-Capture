package com.exchangeinsightscapture.h264;

import java.io.Closeable;
import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.util.ArrayList;
import java.util.List;

/**
 * Writes an MP4 around encoded H.264 frames.
 *
 * <p>An MP4 is a tree of boxes, each a length and a four character type. Almost all of this class
 * is that tree; the interesting decisions are two.
 *
 * <p>The index goes BEFORE the media data. A player cannot begin until it has the index, so with
 * it at the end a browser has to fetch the end of the file first - an extra round trip before
 * anything appears, on every play and every hover preview. The index cannot be written until every
 * frame is encoded, so space for it is reserved up front, the frames are written past it, and the
 * real index is dropped into the gap at the end. Whatever space is left over becomes a free box,
 * which is exactly what that box type exists for.
 *
 * <p>The thumbnail travels inside the file, as cover art. The alternative is a sidecar image next
 * to each clip, which means a second set of files to write, rename, delete and eventually leave
 * behind. Reading it back needs no video decoding at all - just walking the box tree - which is
 * the whole reason it is done this way rather than decoding the first frame on demand.
 */
public final class Mp4Writer implements Closeable
{
	/** Durations are in milliseconds, which is fine for anything up to a few hours. */
	public static final int TIMESCALE = 1000;

	private final RandomAccessFile file;
	private final int width;
	private final int height;
	private final byte[] sps;
	private final byte[] pps;
	private final byte[] thumbnail;

	private final List<int[]> samples = new ArrayList<>(); // size, duration, keyframe
	private final long moovAt;
	private final int moovReserved;
	private final long mdatAt;
	private long dataAt;
	private boolean closed;

	/**
	 * @param thumbnail a JPEG to carry as cover art, or null for none.
	 * @param expectedFrames used only to size the reserved index; being over is harmless, and
	 *                       being under is caught rather than silently corrupting the file.
	 */
	public Mp4Writer(File out, int width, int height, byte[] sps, byte[] pps, byte[] thumbnail,
		int expectedFrames) throws IOException
	{
		this.width = width;
		this.height = height;
		this.sps = sps;
		this.pps = pps;
		this.thumbnail = thumbnail;
		this.file = new RandomAccessFile(out, "rw");
		this.file.setLength(0);

		writeFtyp();

		// Every sample costs an entry in the timing table, one in the size table, and possibly one
		// in the sync table. The rest is fixed overhead plus the thumbnail, with room to spare.
		this.moovReserved = 2048 + (thumbnail == null ? 0 : thumbnail.length)
			+ Math.max(1, expectedFrames) * 16;
		this.moovAt = file.getFilePointer();
		file.write(new byte[moovReserved]);

		this.mdatAt = file.getFilePointer();
		writeInt(0); // placeholder for the media data length
		writeType("mdat");
		this.dataAt = file.getFilePointer();
	}

	/**
	 * Append one encoded frame.
	 *
	 * <p>NALs are written with a four byte length in front rather than the start codes used when
	 * H.264 travels on its own. That is what the sample entry declares, and it is what makes a
	 * sample seekable without scanning for markers.
	 */
	public void addFrame(byte[] nal, int durationMs, boolean keyframe) throws IOException
	{
		writeInt(nal.length);
		file.write(nal);
		samples.add(new int[]{nal.length + 4, Math.max(1, durationMs), keyframe ? 1 : 0});
	}

	public int frames()
	{
		return samples.size();
	}

	@Override
	public void close() throws IOException
	{
		if (closed)
		{
			return;
		}
		closed = true;
		try
		{
			final long end = file.getFilePointer();
			file.seek(mdatAt);
			writeInt((int) (end - mdatAt));
			file.seek(moovAt);

			final byte[] moov = buildMoov();
			if (moov.length > moovReserved)
			{
				throw new IOException("index needs " + moov.length + " bytes, reserved "
					+ moovReserved);
			}
			file.write(moov);

			// Whatever is left of the reservation is declared as free space rather than left as
			// stray bytes, which a parser would otherwise try to read as a box.
			final int slack = moovReserved - moov.length;
			if (slack >= 8)
			{
				writeInt(slack);
				writeType("free");
				file.write(new byte[slack - 8]);
			}
			else if (slack > 0)
			{
				throw new IOException("cannot pad " + slack + " bytes");
			}
			file.seek(end);
		}
		finally
		{
			file.close();
		}
	}

	// ------------------------------------------------------------------ boxes

	private void writeFtyp() throws IOException
	{
		final Box b = new Box("ftyp");
		b.type("isom");
		b.i32(512);
		b.type("isom");
		b.type("iso2");
		b.type("avc1");
		b.type("mp41");
		file.write(b.done());
	}

	private byte[] buildMoov() throws IOException
	{
		long duration = 0;
		for (int[] s : samples)
		{
			duration += s[1];
		}

		final Box moov = new Box("moov");
		moov.raw(mvhd(duration));
		moov.raw(trak(duration));
		if (thumbnail != null)
		{
			moov.raw(udta());
		}
		return moov.done();
	}

	private byte[] mvhd(long duration)
	{
		final Box b = new Box("mvhd");
		b.i32(0);          // version and flags
		b.i32(0);          // creation time
		b.i32(0);          // modification time
		b.i32(TIMESCALE);
		b.i32((int) duration);
		b.i32(0x00010000); // rate: 1.0
		b.i16((short) 0x0100); // volume: 1.0
		b.i16((short) 0);
		b.i32(0);
		b.i32(0);
		b.matrix();
		for (int i = 0; i < 6; i++)
		{
			b.i32(0);
		}
		b.i32(2); // next track id
		return b.done();
	}

	private byte[] trak(long duration)
	{
		final Box b = new Box("trak");
		b.raw(tkhd(duration));
		b.raw(mdia(duration));
		return b.done();
	}

	private byte[] tkhd(long duration)
	{
		final Box b = new Box("tkhd");
		b.i32(7);  // version 0, flags: enabled, in movie, in preview
		b.i32(0);
		b.i32(0);
		b.i32(1);  // track id
		b.i32(0);
		b.i32((int) duration);
		b.i32(0);
		b.i32(0);
		b.i16((short) 0); // layer
		b.i16((short) 0); // alternate group
		b.i16((short) 0); // volume: silent, this is video
		b.i16((short) 0);
		b.matrix();
		b.i32(width << 16);
		b.i32(height << 16);
		return b.done();
	}

	private byte[] mdia(long duration)
	{
		final Box b = new Box("mdia");
		final Box mdhd = new Box("mdhd");
		mdhd.i32(0);
		mdhd.i32(0);
		mdhd.i32(0);
		mdhd.i32(TIMESCALE);
		mdhd.i32((int) duration);
		mdhd.i16((short) 0x55C4); // language: undetermined
		mdhd.i16((short) 0);
		b.raw(mdhd.done());

		final Box hdlr = new Box("hdlr");
		hdlr.i32(0);
		hdlr.i32(0);
		hdlr.type("vide");
		hdlr.i32(0);
		hdlr.i32(0);
		hdlr.i32(0);
		hdlr.bytes("VideoHandler".getBytes(java.nio.charset.StandardCharsets.US_ASCII));
		hdlr.i8(0);
		b.raw(hdlr.done());

		b.raw(minf());
		return b.done();
	}

	private byte[] minf()
	{
		final Box b = new Box("minf");

		final Box vmhd = new Box("vmhd");
		vmhd.i32(1); // version 0, flags 1
		vmhd.i16((short) 0);
		vmhd.i16((short) 0);
		vmhd.i16((short) 0);
		vmhd.i16((short) 0);
		b.raw(vmhd.done());

		final Box dinf = new Box("dinf");
		final Box dref = new Box("dref");
		dref.i32(0);
		dref.i32(1);
		final Box url = new Box("url ");
		url.i32(1); // the media is in this very file
		dref.raw(url.done());
		dinf.raw(dref.done());
		b.raw(dinf.done());

		b.raw(stbl());
		return b.done();
	}

	private byte[] stbl()
	{
		final Box b = new Box("stbl");
		b.raw(stsd());

		// Timing, run-length coded: a clip captured at a steady rate collapses to one entry.
		final List<int[]> runs = new ArrayList<>();
		for (int[] s : samples)
		{
			if (!runs.isEmpty() && runs.get(runs.size() - 1)[1] == s[1])
			{
				runs.get(runs.size() - 1)[0]++;
			}
			else
			{
				runs.add(new int[]{1, s[1]});
			}
		}
		final Box stts = new Box("stts");
		stts.i32(0);
		stts.i32(runs.size());
		for (int[] r : runs)
		{
			stts.i32(r[0]);
			stts.i32(r[1]);
		}
		b.raw(stts.done());

		final List<Integer> sync = new ArrayList<>();
		for (int i = 0; i < samples.size(); i++)
		{
			if (samples.get(i)[2] == 1)
			{
				sync.add(i + 1);
			}
		}
		if (sync.size() != samples.size())
		{
			// Only worth writing when some frames are not seekable; if every frame is a keyframe
			// the absence of this box says exactly that.
			final Box stss = new Box("stss");
			stss.i32(0);
			stss.i32(sync.size());
			for (int i : sync)
			{
				stss.i32(i);
			}
			b.raw(stss.done());
		}

		// One chunk holding every sample, so the offset table is a single entry.
		final Box stsc = new Box("stsc");
		stsc.i32(0);
		stsc.i32(1);
		stsc.i32(1);                 // first chunk
		stsc.i32(samples.size());    // samples in it
		stsc.i32(1);                 // sample description index
		b.raw(stsc.done());

		final Box stsz = new Box("stsz");
		stsz.i32(0);
		stsz.i32(0); // sizes vary, so they are listed
		stsz.i32(samples.size());
		for (int[] s : samples)
		{
			stsz.i32(s[0]);
		}
		b.raw(stsz.done());

		final Box stco = new Box("stco");
		stco.i32(0);
		stco.i32(1);
		stco.i32((int) dataAt);
		b.raw(stco.done());

		return b.done();
	}

	private byte[] stsd()
	{
		final Box b = new Box("stsd");
		b.i32(0);
		b.i32(1); // one entry

		final Box avc1 = new Box("avc1");
		for (int i = 0; i < 6; i++)
		{
			avc1.i8(0);
		}
		avc1.i16((short) 1); // data reference index
		avc1.i16((short) 0);
		avc1.i16((short) 0);
		avc1.i32(0);
		avc1.i32(0);
		avc1.i32(0);
		avc1.i16((short) width);
		avc1.i16((short) height);
		avc1.i32(0x00480000); // 72 dpi
		avc1.i32(0x00480000);
		avc1.i32(0);
		avc1.i16((short) 1); // frames per sample
		final byte[] name = new byte[32];
		avc1.bytes(name);
		avc1.i16((short) 0x0018); // depth
		avc1.i16((short) -1);

		final Box avcc = new Box("avcC");
		avcc.i8(1);
		avcc.i8(sps[1]); // profile
		avcc.i8(sps[2]); // profile compatibility
		avcc.i8(sps[3]); // level
		avcc.i8(0xFF);   // NAL lengths are four bytes
		avcc.i8(0xE1);   // one sequence parameter set
		avcc.i16((short) sps.length);
		avcc.bytes(sps);
		avcc.i8(1);      // one picture parameter set
		avcc.i16((short) pps.length);
		avcc.bytes(pps);
		avc1.raw(avcc.done());

		b.raw(avc1.done());
		return b.done();
	}

	/** The thumbnail, as the cover art box players and taggers already understand. */
	private byte[] udta()
	{
		final Box udta = new Box("udta");
		final Box meta = new Box("meta");
		meta.i32(0); // meta carries a version and flags, unlike most container boxes

		final Box hdlr = new Box("hdlr");
		hdlr.i32(0);
		hdlr.i32(0);
		hdlr.type("mdir");
		hdlr.type("appl");
		hdlr.i32(0);
		hdlr.i32(0);
		hdlr.i8(0);
		meta.raw(hdlr.done());

		final Box ilst = new Box("ilst");
		final Box covr = new Box("covr");
		final Box data = new Box("data");
		data.i32(13); // well known type: JPEG
		data.i32(0);  // locale
		data.bytes(thumbnail);
		covr.raw(data.done());
		ilst.raw(covr.done());
		meta.raw(ilst.done());

		udta.raw(meta.done());
		return udta.done();
	}

	// ------------------------------------------------------------------ plumbing

	private void writeInt(int v) throws IOException
	{
		file.write(new byte[]{(byte) (v >>> 24), (byte) (v >>> 16), (byte) (v >>> 8), (byte) v});
	}

	private void writeType(String type) throws IOException
	{
		file.write(type.getBytes(java.nio.charset.StandardCharsets.US_ASCII));
	}

	/** A box under construction: contents accumulate, and the length is prefixed at the end. */
	private static final class Box
	{
		private final String type;
		private final java.io.ByteArrayOutputStream body = new java.io.ByteArrayOutputStream();

		Box(String type)
		{
			this.type = type;
		}

		void i8(int v)
		{
			body.write(v);
		}

		void i16(short v)
		{
			body.write((v >>> 8) & 0xFF);
			body.write(v & 0xFF);
		}

		void i32(int v)
		{
			body.write((v >>> 24) & 0xFF);
			body.write((v >>> 16) & 0xFF);
			body.write((v >>> 8) & 0xFF);
			body.write(v & 0xFF);
		}

		void type(String t)
		{
			bytes(t.getBytes(java.nio.charset.StandardCharsets.US_ASCII));
		}

		void bytes(byte[] b)
		{
			body.write(b, 0, b.length);
		}

		void raw(byte[] b)
		{
			bytes(b);
		}

		/** The unity transform every player expects when no rotation is intended. */
		void matrix()
		{
			i32(0x00010000);
			i32(0);
			i32(0);
			i32(0);
			i32(0x00010000);
			i32(0);
			i32(0);
			i32(0);
			i32(0x40000000);
		}

		byte[] done()
		{
			final byte[] payload = body.toByteArray();
			final byte[] out = new byte[payload.length + 8];
			final int size = out.length;
			out[0] = (byte) (size >>> 24);
			out[1] = (byte) (size >>> 16);
			out[2] = (byte) (size >>> 8);
			out[3] = (byte) size;
			final byte[] t = type.getBytes(java.nio.charset.StandardCharsets.US_ASCII);
			System.arraycopy(t, 0, out, 4, 4);
			System.arraycopy(payload, 0, out, 8, payload.length);
			return out;
		}
	}
}
