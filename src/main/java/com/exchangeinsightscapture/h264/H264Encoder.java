package com.exchangeinsightscapture.h264;

/**
 * An H.264 encoder, written here because the plugin cannot depend on one.
 *
 * <p>Baseline profile, 4:2:0, CAVLC, one slice per picture, deblocking off. Deliberately small: it
 * encodes what this plugin records and nothing else. No interlacing, no B-frames, no reference
 * reordering, no rate control beyond a fixed quantiser.
 *
 * <p>Frames arrive as three planes at full and quarter resolution. The picture is coded in 16
 * pixel units, so anything not a multiple of 16 is padded and cropped back by the decoder - the
 * client canvas is 1310 wide, which is not, so that is the normal case rather than an edge one.
 */
public final class H264Encoder
{
	private final int width;
	private final int height;
	private final int mbWidth;
	private final int mbHeight;
	private final int paddedWidth;
	private final int paddedHeight;
	private final int chromaWidth;
	private final int qp;
	private final int chromaQp;
	private int idrPicId;

	/** Padded source planes, so a macroblock never reads past the picture. */
	private final byte[] py;
	private final byte[] pu;
	private final byte[] pv;

	/** What the decoder will hold. Prediction reads these, never the source. */
	private final byte[] ry;
	private final byte[] ru;
	private final byte[] rv;

	/**
	 * The previous frame, as the decoder holds it. Predicted frames are coded against this.
	 *
	 * <p>Separate from the working reconstruction because that one is overwritten macroblock by
	 * macroblock as the current frame is coded, and a predicted macroblock needs the PREVIOUS
	 * frame at its own position - which the current frame has usually already trampled.
	 */
	private final byte[] refY;
	private final byte[] refU;
	private final byte[] refV;
	private int frameNum;

	/** Non-zero coefficient counts per 4x4 block, which drive the entropy coder's context. */
	private final int[] nzY;
	private final int[] nzU;
	private final int[] nzV;

	private final int[] block = new int[16];
	private final int[] scan = new int[16];
	private final int[] lumaDc = new int[16];
	private final int[] chromaDc = new int[4];
	private final int[][] lumaAc = new int[16][16];
	private final int[][] chromaAc = new int[8][16];

	/**
	 * Scratch, reused across macroblocks rather than allocated per macroblock.
	 *
	 * <p>A 1310x720 frame is 3690 macroblocks, so at fifty frames a second these were being
	 * allocated and thrown away around two million times a second. None of them outlive the
	 * macroblock they are used in, and a frame is encoded on one thread, so one copy each will do.
	 */
	private final int[] chromaPred = new int[8];
	private final int[][] chromaDcLevels = new int[2][4];
	private final int[] dcScratch = new int[16];
	private final int[] cdcScratch = new int[4];

	public H264Encoder(int width, int height, int qp)
	{
		this.width = width;
		this.height = height;
		this.qp = Math.max(0, Math.min(51, qp));
		this.chromaQp = Transform.chromaQp(this.qp);
		this.mbWidth = (width + 15) / 16;
		this.mbHeight = (height + 15) / 16;
		this.paddedWidth = mbWidth * 16;
		this.paddedHeight = mbHeight * 16;
		this.chromaWidth = paddedWidth / 2;
		this.py = new byte[paddedWidth * paddedHeight];
		this.pu = new byte[paddedWidth * paddedHeight / 4];
		this.pv = new byte[paddedWidth * paddedHeight / 4];
		this.ry = new byte[py.length];
		this.ru = new byte[pu.length];
		this.rv = new byte[pv.length];
		this.refY = new byte[py.length];
		this.refU = new byte[pu.length];
		this.refV = new byte[pv.length];
		this.nzY = new int[mbWidth * 4 * mbHeight * 4];
		this.nzU = new int[mbWidth * 2 * mbHeight * 2];
		this.nzV = new int[nzU.length];
	}

	/** The sequence parameter set, as an escaped NAL payload including its header byte. */
	public byte[] sps(int fps)
	{
		final Bits b = new Bits();
		b.u(0x67, 8); // nal_ref_idc 3, type 7

		b.u(66, 8);   // baseline
		b.u(0x80, 8); // constraint_set0_flag, rest zero
		b.u(40, 8);   // level 4.0, enough for 1080p at our rates
		b.ue(0);      // seq_parameter_set_id
		b.ue(0);      // log2_max_frame_num_minus4
		b.ue(2);      // pic_order_cnt_type 2: output order is decode order, true with no B-frames
		b.ue(1);      // max_num_ref_frames
		b.u1(0);      // gaps_in_frame_num_value_allowed_flag
		b.ue(mbWidth - 1);
		b.ue(mbHeight - 1);
		b.u1(1);      // frame_mbs_only_flag
		b.u1(1);      // direct_8x8_inference_flag

		final int cropRight = (paddedWidth - width) / 2;
		final int cropBottom = (paddedHeight - height) / 2;
		if (cropRight != 0 || cropBottom != 0)
		{
			b.u1(1);
			b.ue(0);
			b.ue(cropRight);
			b.ue(0);
			b.ue(cropBottom);
		}
		else
		{
			b.u1(0);
		}

		b.u1(1);
		vui(b, fps);
		b.trailing();
		return Bits.escape(b.raw(), b.size());
	}

	/**
	 * Colour signalling, which is not cosmetic.
	 *
	 * <p>Without it players guess, and the guess is usually limited-range BT.709 while these
	 * frames are full-range BT.601. That mismatch is what makes clips look washed out and faintly
	 * off-hue no matter what the encoder does.
	 */
	private void vui(Bits b, int fps)
	{
		b.u1(0);   // aspect_ratio_info_present_flag
		b.u1(0);   // overscan_info_present_flag
		b.u1(1);   // video_signal_type_present_flag
		b.u(5, 3); // video_format: unspecified
		b.u1(1);   // video_full_range_flag
		b.u1(1);   // colour_description_present_flag
		b.u(6, 8); // colour_primaries: BT.601
		b.u(6, 8); // transfer_characteristics: BT.601
		b.u(6, 8); // matrix_coefficients: BT.601
		b.u1(0);   // chroma_loc_info_present_flag

		b.u1(1);    // timing_info_present_flag
		b.u(1, 32); // num_units_in_tick
		b.u(Math.max(1, fps) * 2, 32); // time_scale: ticks are half-frames, hence the doubling
		b.u1(1);    // fixed_frame_rate_flag

		b.u1(0); // nal_hrd_parameters_present_flag
		b.u1(0); // vcl_hrd_parameters_present_flag
		b.u1(0); // pic_struct_present_flag
		b.u1(0); // bitstream_restriction_flag
	}

	/** The picture parameter set. */
	public byte[] pps()
	{
		final Bits b = new Bits();
		b.u(0x68, 8); // nal_ref_idc 3, type 8

		b.ue(0);   // pic_parameter_set_id
		b.ue(0);   // seq_parameter_set_id
		b.u1(0);   // entropy_coding_mode_flag: CAVLC
		b.u1(0);   // bottom_field_pic_order_in_frame_present_flag
		b.ue(0);   // num_slice_groups_minus1
		b.ue(0);   // num_ref_idx_l0_default_active_minus1
		b.ue(0);   // num_ref_idx_l1_default_active_minus1
		b.u1(0);   // weighted_pred_flag
		b.u(0, 2); // weighted_bipred_idc
		b.se(qp - 26);
		b.se(0);   // pic_init_qs_minus26
		b.se(0);   // chroma_qp_index_offset
		b.u1(1);   // deblocking_filter_control_present_flag, so the slice can switch it off
		b.u1(0);   // constrained_intra_pred_flag
		b.u1(0);   // redundant_pic_cnt_present_flag

		b.trailing();
		return Bits.escape(b.raw(), b.size());
	}

	/** Encode one frame as an IDR, every macroblock I_16x16 with DC prediction. */
	public byte[] encodeIdr(byte[] y, byte[] u, byte[] v)
	{
		pad(y, u, v);
		java.util.Arrays.fill(nzY, 0);
		java.util.Arrays.fill(nzU, 0);
		java.util.Arrays.fill(nzV, 0);

		final Bits b = new Bits();
		b.u(0x65, 8); // nal_ref_idc 3, type 5 (IDR)
		b.ue(0);  // first_mb_in_slice
		b.ue(7);  // slice_type: I, and every slice in the picture is I
		b.ue(0);  // pic_parameter_set_id
		b.u(0, 4); // frame_num
		b.ue(idrPicId);
		b.u1(0);  // no_output_of_prior_pics_flag
		b.u1(0);  // long_term_reference_flag
		b.se(0);  // slice_qp_delta
		b.ue(1);  // disable_deblocking_filter_idc: off

		for (int mbY = 0; mbY < mbHeight; mbY++)
		{
			for (int mbX = 0; mbX < mbWidth; mbX++)
			{
				macroblock(b, mbX, mbY);
			}
		}

		b.trailing();
		idrPicId = (idrPicId + 1) & 0xFFFF;
		frameNum = 1;
		publishReference();
		return Bits.escape(b.raw(), b.size());
	}

	/** Hand the finished picture to the next frame to predict from. */
	private void publishReference()
	{
		System.arraycopy(ry, 0, refY, 0, ry.length);
		System.arraycopy(ru, 0, refU, 0, ru.length);
		System.arraycopy(rv, 0, refV, 0, rv.length);
	}

	private void macroblock(Bits b, int mbX, int mbY)
	{
		final boolean left = mbX > 0;
		final boolean top = mbY > 0;
		final int lx = mbX * 16;
		final int ly = mbY * 16;
		final int cx = mbX * 8;
		final int cy = mbY * 8;

		// --- luma: predict, transform, quantise ---
		final int lumaPred = Macroblocks.lumaDcPredict(ry, paddedWidth, lx, ly, left, top);
		boolean anyLumaAc = false;
		for (int blk = 0; blk < 16; blk++)
		{
			final int bx = Macroblocks.BLK_X[blk];
			final int by = Macroblocks.BLK_Y[blk];
			for (int j = 0; j < 4; j++)
			{
				for (int i = 0; i < 4; i++)
				{
					final int px = py[(ly + by * 4 + j) * paddedWidth + lx + bx * 4 + i] & 0xFF;
					block[j * 4 + i] = px - lumaPred;
				}
			}
			Transform.forward(block);
			lumaDc[by * 4 + bx] = block[0];
			final int[] ac = lumaAc[blk];
			ac[0] = 0;
			for (int i = 1; i < 16; i++)
			{
				ac[i] = Transform.quant(block[i], i, qp);
				anyLumaAc |= ac[i] != 0;
			}
		}
		Transform.hadamard4(lumaDc);
		for (int i = 0; i < 16; i++)
		{
			lumaDc[i] = Transform.quantLumaDc(lumaDc[i], qp);
		}
		final int cbpLuma = anyLumaAc ? 15 : 0;

		// --- chroma: the same, per component ---
		boolean anyChromaAc = false;
		boolean anyChromaDc = false;
		final int[][] dcPerComponent = chromaDcLevels;
		for (int comp = 0; comp < 2; comp++)
		{
			final byte[] src = comp == 0 ? pu : pv;
			final byte[] rec = comp == 0 ? ru : rv;
			for (int blk = 0; blk < 4; blk++)
			{
				final int bx = blk & 1;
				final int by = blk >> 1;
				final int pred = Macroblocks.chromaDcPredict(rec, chromaWidth, cx, cy, bx, by, left, top);
				chromaPred[comp * 4 + blk] = pred;
				for (int j = 0; j < 4; j++)
				{
					for (int i = 0; i < 4; i++)
					{
						final int px = src[(cy + by * 4 + j) * chromaWidth + cx + bx * 4 + i] & 0xFF;
						block[j * 4 + i] = px - pred;
					}
				}
				Transform.forward(block);
				dcPerComponent[comp][by * 2 + bx] = block[0];
				final int[] ac = chromaAc[comp * 4 + blk];
				ac[0] = 0;
				for (int i = 1; i < 16; i++)
				{
					ac[i] = Transform.quant(block[i], i, chromaQp);
					anyChromaAc |= ac[i] != 0;
				}
			}
			Transform.hadamard2(dcPerComponent[comp]);
			for (int i = 0; i < 4; i++)
			{
				dcPerComponent[comp][i] = Transform.quantDc(dcPerComponent[comp][i], chromaQp);
				anyChromaDc |= dcPerComponent[comp][i] != 0;
			}
		}
		final int cbpChroma = anyChromaAc ? 2 : anyChromaDc ? 1 : 0;

		// --- write it ---
		// I_16x16 mb_type packs the prediction mode and both coded block patterns into one code.
		b.ue(1 + 2 + 4 * cbpChroma + 12 * (cbpLuma != 0 ? 1 : 0));
		b.ue(0); // intra_chroma_pred_mode: DC
		b.se(0); // mb_qp_delta

		Macroblocks.toZigzag(lumaDc, scan, 0);
		Cavlc.block(b, scan, 16, lumaNc(mbX * 4, mbY * 4));

		if (cbpLuma != 0)
		{
			for (int blk = 0; blk < 16; blk++)
			{
				final int bx = mbX * 4 + Macroblocks.BLK_X[blk];
				final int by = mbY * 4 + Macroblocks.BLK_Y[blk];
				Macroblocks.toZigzag(lumaAc[blk], scan, 1);
				nzY[by * mbWidth * 4 + bx] = Cavlc.block(b, scan, 15, lumaNc(bx, by));
			}
		}

		if (cbpChroma != 0)
		{
			for (int comp = 0; comp < 2; comp++)
			{
				System.arraycopy(dcPerComponent[comp], 0, chromaDc, 0, 4);
				Cavlc.block(b, chromaDc, 4, -1);
			}
		}
		if (cbpChroma == 2)
		{
			for (int comp = 0; comp < 2; comp++)
			{
				final int[] nz = comp == 0 ? nzU : nzV;
				for (int blk = 0; blk < 4; blk++)
				{
					final int bx = mbX * 2 + (blk & 1);
					final int by = mbY * 2 + (blk >> 1);
					Macroblocks.toZigzag(chromaAc[comp * 4 + blk], scan, 1);
					nz[by * mbWidth * 2 + bx] = Cavlc.block(b, scan, 15, chromaNc(nz, bx, by));
				}
			}
		}

		reconstruct(mbX, mbY, lumaPred, cbpLuma, cbpChroma, chromaPred, dcPerComponent);
	}

	/**
	 * Rebuild the macroblock exactly as the decoder will, and store it for later prediction.
	 *
	 * <p>Runs even when nothing was coded: a macroblock whose residual quantised entirely to zero
	 * still has to leave its prediction behind, because the next macroblock predicts from it.
	 */
	private void reconstruct(int mbX, int mbY, int lumaPred, int cbpLuma, int cbpChroma,
		int[] chromaPred, int[][] dcPerComponent)
	{
		final int lx = mbX * 16;
		final int ly = mbY * 16;

		final int[] dc = dcScratch;
		System.arraycopy(lumaDc, 0, dc, 0, 16);
		Transform.hadamard4(dc);
		for (int i = 0; i < 16; i++)
		{
			dc[i] = Transform.dequantLumaDc(dc[i], qp);
		}

		for (int blk = 0; blk < 16; blk++)
		{
			final int bx = Macroblocks.BLK_X[blk];
			final int by = Macroblocks.BLK_Y[blk];
			block[0] = dc[by * 4 + bx];
			for (int i = 1; i < 16; i++)
			{
				block[i] = cbpLuma != 0 ? Transform.dequant(lumaAc[blk][i], i, qp) : 0;
			}
			Transform.inverse(block);
			for (int j = 0; j < 4; j++)
			{
				for (int i = 0; i < 4; i++)
				{
					final int at = (ly + by * 4 + j) * paddedWidth + lx + bx * 4 + i;
					ry[at] = (byte) Macroblocks.clamp(lumaPred + block[j * 4 + i]);
				}
			}
		}

		final int cx = mbX * 8;
		final int cy = mbY * 8;
		for (int comp = 0; comp < 2; comp++)
		{
			final byte[] rec = comp == 0 ? ru : rv;
			final int[] cdc = cdcScratch;
			System.arraycopy(dcPerComponent[comp], 0, cdc, 0, 4);
			Transform.hadamard2(cdc);
			for (int i = 0; i < 4; i++)
			{
				cdc[i] = Transform.dequantChromaDc(cdc[i], chromaQp);
			}
			for (int blk = 0; blk < 4; blk++)
			{
				final int bx = blk & 1;
				final int by = blk >> 1;
				block[0] = cbpChroma != 0 ? cdc[by * 2 + bx] : 0;
				for (int i = 1; i < 16; i++)
				{
					block[i] = cbpChroma == 2
						? Transform.dequant(chromaAc[comp * 4 + blk][i], i, chromaQp)
						: 0;
				}
				Transform.inverse(block);
				final int pred = chromaPred[comp * 4 + blk];
				for (int j = 0; j < 4; j++)
				{
					for (int i = 0; i < 4; i++)
					{
						final int at = (cy + by * 4 + j) * chromaWidth + cx + bx * 4 + i;
						rec[at] = (byte) Macroblocks.clamp(pred + block[j * 4 + i]);
					}
				}
			}
		}
	}

	/**
	 * The entropy coder's context for a luma block: how busy its neighbours were.
	 *
	 * <p>Left and above are always already coded, both within a macroblock and across them, so
	 * availability is simply whether the block is inside the picture.
	 */
	private int lumaNc(int bx, int by)
	{
		final int stride = mbWidth * 4;
		final boolean left = bx > 0;
		final boolean top = by > 0;
		final int a = left ? nzY[by * stride + bx - 1] : 0;
		final int c = top ? nzY[(by - 1) * stride + bx] : 0;
		if (left && top)
		{
			return (a + c + 1) >> 1;
		}
		return left ? a : top ? c : 0;
	}

	private int chromaNc(int[] nz, int bx, int by)
	{
		final int stride = mbWidth * 2;
		final boolean left = bx > 0;
		final boolean top = by > 0;
		final int a = left ? nz[by * stride + bx - 1] : 0;
		final int c = top ? nz[(by - 1) * stride + bx] : 0;
		if (left && top)
		{
			return (a + c + 1) >> 1;
		}
		return left ? a : top ? c : 0;
	}

	/**
	 * coded_block_pattern is coded as a mapped index rather than written directly, because the
	 * common patterns are not the small numbers. This is the standard inter mapping, inverted:
	 * pattern to index. Index 0 is pattern 0, so an all-zero macroblock stays cheap.
	 */
	private static final int[] CBP_TO_CODE = new int[48];

	static
	{
		final int[] codeToCbp = {
			0, 16, 1, 2, 4, 8, 32, 3, 5, 10, 12, 15, 47, 7, 11, 13, 14, 6, 9, 31, 35, 37, 42, 44,
			33, 34, 36, 40, 39, 43, 45, 46, 17, 18, 20, 24, 19, 21, 26, 28, 23, 27, 29, 30, 22,
			25, 38, 41,
		};
		for (int i = 0; i < codeToCbp.length; i++)
		{
			CBP_TO_CODE[codeToCbp[i]] = i;
		}
	}

	/**
	 * Encode one frame as a predicted picture, against the frame before it.
	 *
	 * <p>Motion vectors are fixed at zero, so a macroblock is predicted from the same position in
	 * the previous frame and motion compensation is a straight copy - no sub-pixel interpolation
	 * and no search. That sounds like a severe limitation and is barely one here: a game canvas is
	 * a still interface over a scene that changes in places, so the parts that do not move are
	 * exactly the parts that dominate the frame, and each of those costs a single skip. What it
	 * gives up is panning, where a real motion search would earn its keep.
	 *
	 * <p>A macroblock whose residual quantises away entirely is skipped, costing nothing but its
	 * share of a run length. That is where the saving over coding every frame whole comes from.
	 */
	public byte[] encodeP(byte[] y, byte[] u, byte[] v)
	{
		pad(y, u, v);
		java.util.Arrays.fill(nzY, 0);
		java.util.Arrays.fill(nzU, 0);
		java.util.Arrays.fill(nzV, 0);

		final Bits b = new Bits();
		b.u(0x41, 8); // nal_ref_idc 2, type 1: a non-IDR picture that is itself a reference
		b.ue(0);      // first_mb_in_slice
		b.ue(5);      // slice_type: P, and every slice in the picture is P
		b.ue(0);      // pic_parameter_set_id
		b.u(frameNum & 15, 4);
		b.u1(0);      // num_ref_idx_active_override_flag
		b.u1(0);      // ref_pic_list_modification_flag_l0
		b.u1(0);      // adaptive_ref_pic_marking_mode_flag: sliding window
		b.se(0);      // slice_qp_delta
		b.ue(1);      // disable_deblocking_filter_idc: off

		int skipRun = 0;
		for (int mbY = 0; mbY < mbHeight; mbY++)
		{
			for (int mbX = 0; mbX < mbWidth; mbX++)
			{
				if (predictedMacroblock(b, mbX, mbY, skipRun))
				{
					skipRun = 0;
				}
				else
				{
					skipRun++;
				}
			}
		}
		// A run that reaches the end of the picture still has to be declared.
		if (skipRun > 0)
		{
			b.ue(skipRun);
		}

		b.trailing();
		frameNum = (frameNum + 1) & 15;
		publishReference();
		return Bits.escape(b.raw(), b.size());
	}

	/**
	 * Code one predicted macroblock, or decide it does not need coding at all.
	 *
	 * @return true if it was written, false if it was skipped.
	 */
	private boolean predictedMacroblock(Bits b, int mbX, int mbY, int skipRun)
	{
		final int lx = mbX * 16;
		final int ly = mbY * 16;
		final int cx = mbX * 8;
		final int cy = mbY * 8;

		// Luma as sixteen ordinary 4x4 blocks - no separate DC, which is an intra-only layout.
		int cbpLuma = 0;
		for (int blk = 0; blk < 16; blk++)
		{
			final int bx = Macroblocks.BLK_X[blk];
			final int by = Macroblocks.BLK_Y[blk];
			for (int j = 0; j < 4; j++)
			{
				for (int i = 0; i < 4; i++)
				{
					final int at = (ly + by * 4 + j) * paddedWidth + lx + bx * 4 + i;
					block[j * 4 + i] = (py[at] & 0xFF) - (refY[at] & 0xFF);
				}
			}
			Transform.forward(block);
			final int[] out = lumaAc[blk];
			boolean any = false;
			for (int i = 0; i < 16; i++)
			{
				out[i] = Transform.quant(block[i], i, qp);
				any |= out[i] != 0;
			}
			if (any)
			{
				cbpLuma |= 1 << (blk / 4);
			}
		}

		final int[][] dcPerComponent = chromaDcLevels;
		boolean anyChromaAc = false;
		boolean anyChromaDc = false;
		for (int comp = 0; comp < 2; comp++)
		{
			final byte[] src = comp == 0 ? pu : pv;
			final byte[] ref = comp == 0 ? refU : refV;
			for (int blk = 0; blk < 4; blk++)
			{
				final int bx = blk & 1;
				final int by = blk >> 1;
				for (int j = 0; j < 4; j++)
				{
					for (int i = 0; i < 4; i++)
					{
						final int at = (cy + by * 4 + j) * chromaWidth + cx + bx * 4 + i;
						block[j * 4 + i] = (src[at] & 0xFF) - (ref[at] & 0xFF);
					}
				}
				Transform.forward(block);
				dcPerComponent[comp][by * 2 + bx] = block[0];
				final int[] ac = chromaAc[comp * 4 + blk];
				ac[0] = 0;
				for (int i = 1; i < 16; i++)
				{
					ac[i] = Transform.quant(block[i], i, chromaQp);
					anyChromaAc |= ac[i] != 0;
				}
			}
			Transform.hadamard2(dcPerComponent[comp]);
			for (int i = 0; i < 4; i++)
			{
				dcPerComponent[comp][i] = Transform.quantDc(dcPerComponent[comp][i], chromaQp);
				anyChromaDc |= dcPerComponent[comp][i] != 0;
			}
		}
		final int cbpChroma = anyChromaAc ? 2 : anyChromaDc ? 1 : 0;

		if (cbpLuma == 0 && cbpChroma == 0)
		{
			// Nothing survived the quantiser, so the prediction alone is the answer. A skipped
			// macroblock reconstructs to exactly that, which is what makes it free.
			copyFromReference(mbX, mbY);
			return false;
		}

		b.ue(skipRun);
		b.ue(0);  // mb_type 0: P_L0_16x16
		b.se(0);  // mvd_l0 x
		b.se(0);  // mvd_l0 y
		b.ue(CBP_TO_CODE[cbpLuma + 16 * cbpChroma]);
		b.se(0);  // mb_qp_delta

		for (int idx8x8 = 0; idx8x8 < 4; idx8x8++)
		{
			if ((cbpLuma & (1 << idx8x8)) == 0)
			{
				continue;
			}
			for (int i = 0; i < 4; i++)
			{
				final int blk = idx8x8 * 4 + i;
				final int bx = mbX * 4 + Macroblocks.BLK_X[blk];
				final int by = mbY * 4 + Macroblocks.BLK_Y[blk];
				Macroblocks.toZigzag(lumaAc[blk], scan, 0);
				nzY[by * mbWidth * 4 + bx] = Cavlc.block(b, scan, 16, lumaNc(bx, by));
			}
		}

		if (cbpChroma != 0)
		{
			for (int comp = 0; comp < 2; comp++)
			{
				System.arraycopy(dcPerComponent[comp], 0, chromaDc, 0, 4);
				Cavlc.block(b, chromaDc, 4, -1);
			}
		}
		if (cbpChroma == 2)
		{
			for (int comp = 0; comp < 2; comp++)
			{
				final int[] nz = comp == 0 ? nzU : nzV;
				for (int blk = 0; blk < 4; blk++)
				{
					final int bx = mbX * 2 + (blk & 1);
					final int by = mbY * 2 + (blk >> 1);
					Macroblocks.toZigzag(chromaAc[comp * 4 + blk], scan, 1);
					nz[by * mbWidth * 2 + bx] = Cavlc.block(b, scan, 15, chromaNc(nz, bx, by));
				}
			}
		}

		reconstructPredicted(mbX, mbY, cbpLuma, cbpChroma, dcPerComponent);
		return true;
	}

	/** A skipped macroblock: the prediction, carried through unchanged. */
	private void copyFromReference(int mbX, int mbY)
	{
		for (int j = 0; j < 16; j++)
		{
			final int at = (mbY * 16 + j) * paddedWidth + mbX * 16;
			System.arraycopy(refY, at, ry, at, 16);
		}
		for (int j = 0; j < 8; j++)
		{
			final int at = (mbY * 8 + j) * chromaWidth + mbX * 8;
			System.arraycopy(refU, at, ru, at, 8);
			System.arraycopy(refV, at, rv, at, 8);
		}
	}

	/** Rebuild a predicted macroblock: the reference plus whatever residual survived. */
	private void reconstructPredicted(int mbX, int mbY, int cbpLuma, int cbpChroma,
		int[][] dcPerComponent)
	{
		final int lx = mbX * 16;
		final int ly = mbY * 16;
		for (int blk = 0; blk < 16; blk++)
		{
			final int bx = Macroblocks.BLK_X[blk];
			final int by = Macroblocks.BLK_Y[blk];
			final boolean coded = (cbpLuma & (1 << (blk / 4))) != 0;
			if (coded)
			{
				for (int i = 0; i < 16; i++)
				{
					block[i] = Transform.dequant(lumaAc[blk][i], i, qp);
				}
				Transform.inverse(block);
			}
			for (int j = 0; j < 4; j++)
			{
				for (int i = 0; i < 4; i++)
				{
					final int at = (ly + by * 4 + j) * paddedWidth + lx + bx * 4 + i;
					ry[at] = (byte) Macroblocks.clamp((refY[at] & 0xFF)
						+ (coded ? block[j * 4 + i] : 0));
				}
			}
		}

		final int cx = mbX * 8;
		final int cy = mbY * 8;
		for (int comp = 0; comp < 2; comp++)
		{
			final byte[] rec = comp == 0 ? ru : rv;
			final byte[] ref = comp == 0 ? refU : refV;
			final int[] cdc = cdcScratch;
			System.arraycopy(dcPerComponent[comp], 0, cdc, 0, 4);
			Transform.hadamard2(cdc);
			for (int i = 0; i < 4; i++)
			{
				cdc[i] = Transform.dequantChromaDc(cdc[i], chromaQp);
			}
			for (int blk = 0; blk < 4; blk++)
			{
				final int bx = blk & 1;
				final int by = blk >> 1;
				block[0] = cbpChroma != 0 ? cdc[by * 2 + bx] : 0;
				for (int i = 1; i < 16; i++)
				{
					block[i] = cbpChroma == 2
						? Transform.dequant(chromaAc[comp * 4 + blk][i], i, chromaQp)
						: 0;
				}
				Transform.inverse(block);
				for (int j = 0; j < 4; j++)
				{
					for (int i = 0; i < 4; i++)
					{
						final int at = (cy + by * 4 + j) * chromaWidth + cx + bx * 4 + i;
						rec[at] = (byte) Macroblocks.clamp((ref[at] & 0xFF) + block[j * 4 + i]);
					}
				}
			}
		}
	}

	private void pad(byte[] y, byte[] u, byte[] v)
	{
		plane(y, py, width, height, paddedWidth, paddedHeight);
		plane(u, pu, (width + 1) / 2, (height + 1) / 2, chromaWidth, paddedHeight / 2);
		plane(v, pv, (width + 1) / 2, (height + 1) / 2, chromaWidth, paddedHeight / 2);
	}

	/**
	 * Copy a plane into its padded buffer, extending the last row and column outward.
	 *
	 * <p>Repeating the edge rather than filling with black matters: the padding is coded like any
	 * other pixels, and a hard black edge is expensive and can bleed back across the crop.
	 */
	private static void plane(byte[] src, byte[] dst, int w, int h, int pw, int ph)
	{
		for (int row = 0; row < ph; row++)
		{
			final int from = Math.min(row, h - 1) * w;
			final int to = row * pw;
			System.arraycopy(src, from, dst, to, w);
			for (int x = w; x < pw; x++)
			{
				dst[to + x] = dst[to + w - 1];
			}
		}
	}
}
