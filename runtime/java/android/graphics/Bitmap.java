// android.graphics.Bitmap -- reference type + AWT-backed Canvas target.
//
// Two modes of operation, chosen by which constructor path created
// the Bitmap (the constructor itself is package-private; entry points
// are createBitmap(w,h,Config) and BitmapFactory.decode*):
//
//   1. Passive carrier for an ARGB pixel buffer that BitmapFactory
//      decoded from a PNG/JPEG. argbBuffer is set by
//      macapkSetArgbBuffer; the GL upload path reads it directly.
//      AndEngine et al. take this path and never touch awtImage.
//
//   2. Canvas drawing target. createBitmapWithCanvasBacking allocates
//      a real BufferedImage; Canvas.drawText etc. paint into it via
//      java.awt.Graphics2D. Cocos2dxBitmap.createTextBitmap uses this
//      path to rasterize CCLabelTTF text into a texture buffer.
//
// AndEngine-safety note: createBitmap(w, h, Config) MUST stay as a
// no-allocation reference type. Earlier versions allocated a
// BufferedImage here unconditionally, which broke AndEngine's texture
// upload path -- AndEngine uses createBitmap+setPixels for sprite
// atlases and depends on getPixel/getPixels reading argbBuffer
// directly. With an AWT backing pre-allocated, macapkArgbBuffer()'s
// lazy AWT->argb extraction overwrote whatever AndEngine had filled
// in via setPixels, producing black sprites. Cocos2dxBitmap callers
// that DO need AWT backing must call createBitmapWithCanvasBacking.
package android.graphics;

import java.awt.image.BufferedImage;

public final class Bitmap implements android.os.Parcelable {
    /** AOSP Bitmap.Config (frameworks/base/graphics/java/android/graphics/Bitmap.java).
     *  RGBA_1010102 is API 33; it goes LAST because ordinal() is observable and
     *  every existing constant's ordinal is part of the ABI. */
    public enum Config {
        ALPHA_8, RGB_565, ARGB_4444, ARGB_8888, RGBA_F16, HARDWARE, RGBA_1010102
    }
    /** AOSP CompressFormat (android-11.0.0_r48 Bitmap.java:1361-1404) declares
     *  FIVE constants, in this order: JPEG(0) PNG(1) WEBP(2) WEBP_LOSSY(3)
     *  WEBP_LOSSLESS(4). We shipped only the first three, so an app compiled
     *  against API 30 that names either of the two API-30 constants -- which is
     *  what the platform's own deprecation notice on WEBP tells it to do --
     *  got NoSuchFieldError at the call site rather than an encode.
     *  MEASURED (BMP.geom.compressEnum): the emulator answers
     *  "n=5 JPEG PNG WEBP WEBP_LOSSY WEBP_LOSSLESS"; we answered n=3, and
     *  BMP.rt.webpLossless / BMP.lossy.webpLossy.shape were both
     *  THROW:java.lang.NoSuchFieldError. The ORDER is API (ordinal() is
     *  observable), so the two new ones go at the END. */
    public enum CompressFormat { JPEG, PNG, WEBP, WEBP_LOSSY, WEBP_LOSSLESS }

    /** AOSP Bitmap.DENSITY_NONE: the bitmap carries no density, which
     *  scaleFromDensity reads as "do not scale". */
    public static final int DENSITY_NONE = 0;

    private int width;
    private int height;
    private Config config;
    private long nativeHandle;
    /** AOSP Bitmap.mNativePtr's allocation size, queried once at native
     *  construction and never re-queried (see getAllocationByteCount's own
     *  doc below). -1 means "not yet established by a construction path" --
     *  getAllocationByteCount() falls back to the live getByteCount() in
     *  that case, which is what every construction path produced before
     *  this field existed, so an uncovered path degrades to the OLD
     *  (buggy but at-least-not-worse) behavior rather than a wrong throw. */
    private int allocationByteCount = -1;
    /** ARGB pixel buffer, length width*height. Populated by
     *  BitmapFactory.decode* OR by macapkExtractArgbFromAwt(). */
    private int[] argbBuffer;
    /** Backing BufferedImage when this Bitmap was created as a Canvas
     *  drawing target via createBitmapWithCanvasBacking. Stays null
     *  for createBitmap(w,h,Config) and BitmapFactory paths so
     *  AndEngine's sprite atlas flow is unaffected. */
    private BufferedImage awtImage;
    /** v1.0.426 Tier-2: AOSP mutability. createBitmap(...) returns a MUTABLE
     *  bitmap; BitmapFactory.decode* returns an IMMUTABLE one. Was hardwired
     *  false, so code that checks isMutable() before drawing into / reconfiguring
     *  a freshly created bitmap (Glide/Coil/Compose, Canvas(bitmap)) bailed. */
    private boolean mutable = false;
    /** AOSP does NOT store hasAlpha and isPremultiplied as two independent
     *  booleans: both are VIEWS of the single SkAlphaType on the bitmap's
     *  SkImageInfo (libs/hwui/jni/Bitmap.cpp -- Bitmap_hasAlpha is
     *  `!info().isOpaque()`, Bitmap_isPremultiplied is
     *  `alphaType() == kPremul_SkAlphaType`, and both setters funnel through
     *  Bitmap::setAlphaType, which runs Skia's SkColorTypeValidateAlphaType
     *  against the COLOR TYPE). Modelling them as two booleans gets the
     *  INTERACTIONS wrong, which is what the probe measured:
     *  setPremultiplied(false) then setHasAlpha(true) must leave
     *  isPremultiplied() FALSE (BMP.state.premul.thenHasAlpha), and
     *  setHasAlpha(true) on an RGB_565 bitmap must be IGNORED
     *  (BMP.state.hasAlpha.565). See macapkValidateAlphaType. */
    private static final int ALPHA_OPAQUE = 0, ALPHA_PREMUL = 1, ALPHA_UNPREMUL = 2;
    private int alphaType = ALPHA_PREMUL;
    /** AOSP Bitmap.mRequestPremultiplied: the last value the app asked for via
     *  setPremultiplied / Options.inPremultiplied, remembered separately from
     *  the live alpha type because setHasAlpha(true) has to restore it
     *  (`nativeSetHasAlpha(ptr, hasAlpha, mRequestPremultiplied)`), and because
     *  copy() carries it to the destination. */
    private boolean requestPremultiplied = true;
    /** AOSP Bitmap.mRecycled. recycle() was a no-op returning isRecycled()
     *  false forever, so every documented "throws IllegalStateException if the
     *  bitmap has been recycled" contract silently succeeded and an app's
     *  `if (bm.isRecycled()) reload()` guard never fired. */
    private boolean recycled = false;
    /** AOSP keeps the source density per-bitmap (Bitmap.mDensity, initialized
     *  from DisplayMetrics.DENSITY_DEVICE) and getScaledWidth/getScaledHeight
     *  scale FROM it. A hardcoded getter with a no-op setter silently
     *  discarded every density a decode or an app assigned, so a bitmap
     *  decoded out of an mdpi drawable claimed to be xhdpi. The device bucket
     *  is DisplayMetrics.DENSITY_DEVICE (xhdpi on Retina, mdpi on a 1x monitor). */
    private int density = macapkDefaultDensity();
    /** AOSP Bitmap.getDefaultDensity(), which IS the device density and is what
     *  a freshly created bitmap and a reused (inBitmap) decode both take.
     *  Exposed package-private so BitmapFactory's setDensityFromOptions
     *  else-branch resets to the SAME value the field initializer uses; two
     *  independent constants would silently drift. */
    static int macapkDefaultDensity() { return android.util.DisplayMetrics.DENSITY_DEVICE; }
    /** AOSP setHasMipMap is a renderer HINT ("indicates whether the renderer
     *  should attempt to use mipmaps"), stored on the bitmap and advisory even
     *  on real Android. We store it and report it back; neither the AWT nor
     *  the GL upload path builds a mip chain. */
    private boolean hasMipMap = false;
    /** AOSP mNinePatchChunk: the raw 9-patch chunk attached at decode time,
     *  null for every bitmap that did not come from a .9.png. */
    private byte[] ninePatchChunk;

    Bitmap() {}
    /** Plain reference-type bitmap. AndEngine uses this. Caller
     *  populates pixel data through macapkSetArgbBuffer or
     *  setPixels(). NO AWT backing -- preserves the HEAD behavior
     *  that AndEngine's texture upload path relies on. */
    public static Bitmap createBitmap(int w, int h, Config c) {
        return createBitmap(w, h, c, true);
    }

    /** Skia's SkColorTypeValidateAlphaType, reduced to the colour types our
     *  Config enum can name. This is what makes hasAlpha/isPremultiplied answer
     *  correctly WITHOUT a per-caller special case:
     *   - RGB_565 has no alpha channel at all, so every alpha type collapses to
     *     opaque ("alphaType = kOpaque_SkAlphaType" for kRGB_565/kGray_8/
     *     kRGB_888x). That is why setHasAlpha(true) on a 565 bitmap is a no-op,
     *     as the AOSP javadoc says ("for some configs (e.g. RGB_565) this call
     *     is ignored").
     *   - ALPHA_8 (kAlpha_8) rewrites kUnpremul to kPremul: with no colour
     *     channels there is nothing to un-premultiply, so an unpremultiplied
     *     A8 is not a representable state. That is why setPremultiplied(false)
     *     on an ALPHA_8 bitmap does nothing (BMP.state.premul.alpha8 = d=1 f=1
     *     t=1) while the same call on ARGB_8888 works (d=1 f=0 t=1). */
    private static int macapkValidateAlphaType(Config c, int t) {
        if (c == Config.RGB_565) return ALPHA_OPAQUE;
        if (c == Config.ALPHA_8 && t == ALPHA_UNPREMUL) return ALPHA_PREMUL;
        return t;
    }
    private void macapkSetAlphaType(int t) {
        this.alphaType = macapkValidateAlphaType(this.config, t);
    }

    /** AOSP createBitmap(display, w, h, config, hasAlpha, colorSpace)
     *  (android-11.0.0_r48 Bitmap.java:1110-1135) -- the ONE allocation path
     *  every other w/h/Config overload funnels into, reproduced argument check
     *  for argument check. Three things were missing and each is an answer an
     *  app reads:
     *   1. checkWidthHeight. A non-positive dimension is an
     *      IllegalArgumentException raised in Java before any allocator sees it
     *      (BMP.geom.create.zeroWidth/zeroHeight/negWidth/negHeight/bothZero
     *      all measured THROW on the emulator against a 0x8 / 8x-1 bitmap
     *      here). A silently-created 0-sized bitmap then fails much later,
     *      somewhere unrelated.
     *   2. The `hasAlpha` argument was DISCARDED by all five overloads that
     *      take it. AOSP both stamps the flag and, for ARGB_8888 / RGBA_F16,
     *      ERASES the new bitmap to opaque black -- so
     *      createBitmap(w,h,ARGB_8888,false) starts at 0xff000000, not
     *      transparent (BMP.state.initAlpha.8888.false).
     *   3. The colour-space null check, which is legal only for ALPHA_8
     *      (BMP.create.nullColorSpace). */
    public static Bitmap createBitmap(android.util.DisplayMetrics display, int w, int h,
                                      Config c, boolean hasAlpha,
                                      android.graphics.ColorSpace cs) {
        if (w <= 0 || h <= 0) {
            throw new IllegalArgumentException("width and height must be > 0");
        }
        // AOSP: a HARDWARE bitmap lives in graphics memory and is always
        // immutable, so the mutable-allocation entry points reject it outright
        // rather than handing back something that cannot exist.
        if (c == Config.HARDWARE) {
            throw new IllegalArgumentException("can't create mutable bitmap with Config.HARDWARE");
        }
        if (cs == null && c != Config.ALPHA_8) {
            throw new IllegalArgumentException("can't create bitmap without a color space");
        }
        Bitmap b = new Bitmap();
        b.width = w; b.height = h; b.config = c;
        b.mutable = true;   // AOSP: createBitmap returns a mutable bitmap
        b.requestPremultiplied = true;
        b.macapkSetAlphaType(ALPHA_PREMUL);
        b.allocationByteCount = b.getByteCount();  // the TRUE, fixed allocation
        if (display != null) b.density = display.densityDpi;
        b.setHasAlpha(hasAlpha);
        if ((c == Config.ARGB_8888 || c == Config.RGBA_F16) && !hasAlpha) {
            // AOSP: nativeErase(bm.mNativePtr, 0xff000000). Deliberately the
            // RAW erase and not eraseColor(), which would re-check mutability
            // and (on a Config we tagged later) re-quantize.
            b.macapkEraseRaw(0xff000000);
        } else if (c == Config.RGB_565) {
            // RGB_565 HAS NO ALPHA CHANNEL, so on a device a freshly allocated
            // one is opaque BLACK for free: Skia zero-fills the allocation, and
            // in 565 all-zero means R=G=B=0 at an implicit alpha of 1.0. AOSP
            // therefore needs no erase here and does not do one -- the pixel
            // FORMAT carries the opacity.
            //
            // We do not have that for free. Every Bitmap in this framework is
            // backed by an ARGB_8888 int[], where all-zero means fully
            // TRANSPARENT. So a new RGB_565 bitmap READ as opaque black
            // (getPixel -> 0xff000000, hasAlpha -> false, because
            // macapkAlphaTypeFor collapses 565 to ALPHA_OPAQUE at :149) while it
            // BLITTED as a no-op. Reading and drawing disagreed, which is the
            // worst shape a bug can have: every probe says the bitmap is fine.
            //
            // Erasing to opaque black at creation makes the backing store agree
            // with the format's semantics. It is not "AOSP does an erase here"
            // -- it is us paying, in the representation we chose, for what the
            // 565 format gives AOSP for nothing.
            //
            // Found via solitaire_cg (net.sourceforge.solitaire_cg): its
            // first-run splash draws into an RGB_565 board bitmap, so we showed
            // GREY where a device shows black. Documented as a known-open nit in
            // CLAUDE.md on 2026-08-12; this closes it.
            b.macapkEraseRaw(0xff000000);
        }
        return b;
    }
    /** AOSP: every createBitmap overload that takes a DisplayMetrics stamps
     *  the new bitmap's density from it (`bm.mDensity = display.densityDpi`)
     *  and leaves the default in place for a null argument. Dropping it made
     *  getScaledWidth/getScaledHeight scale from the wrong source density. */
    private static Bitmap macapkWithDensity(Bitmap b, android.util.DisplayMetrics dm) {
        if (b != null && dm != null) b.density = dm.densityDpi;
        return b;
    }
    /** v1.0.124: AOSP overloads accepting DisplayMetrics + ColorSpace.
     *  Compose UI / drawing paths use these for density-aware bitmaps.
     *  Every one of them is documented as the six-argument form with a default
     *  filled in, so they delegate rather than re-implementing the checks. */
    public static Bitmap createBitmap(android.util.DisplayMetrics dm, int w, int h, Config c) {
        return createBitmap(dm, w, h, c, true);
    }
    public static Bitmap createBitmap(android.util.DisplayMetrics dm, int w, int h,
                                       Config c, boolean hasAlpha) {
        return createBitmap(dm, w, h, c, hasAlpha, macapkDefaultColorSpace(c));
    }
    public static Bitmap createBitmap(int w, int h, Config c, boolean hasAlpha) {
        return createBitmap(null, w, h, c, hasAlpha, macapkDefaultColorSpace(c));
    }
    public static Bitmap createBitmap(int w, int h, Config c, boolean hasAlpha,
                                       android.graphics.ColorSpace cs) {
        return createBitmap(null, w, h, c, hasAlpha, cs);
    }
    /** The colour space the overloads that do NOT take one pass on AOSP:
     *  `ColorSpace.get(Named.SRGB)` everywhere except the ALPHA_8 case, where
     *  a space is meaningless (getColorSpace() answers null for an A8 bitmap,
     *  BMP.state.colorSpace.alpha8) and the six-argument form accepts null. */
    private static android.graphics.ColorSpace macapkDefaultColorSpace(Config c) {
        if (c == Config.ALPHA_8) return null;
        return android.graphics.ColorSpace.get(android.graphics.ColorSpace.Named.SRGB);
    }
    /** The API-34 Ultra-HDR gainmap trio, mirroring
     *  frameworks/base/graphics/java/android/graphics/Bitmap.java:1952-1978
     *  (android14-release). MEASURED as MISSING by a corpus-wide preflight sweep:
     *  2 of the 62 test APKs call hasGainmap(), and a missing method is a
     *  NoSuchMethodError at the call site rather than a graceful false.
     *
     *  We decode through Java2D, which has no notion of an HDR recovery map, so
     *  nothing ever attaches one and hasGainmap() answers false / getGainmap()
     *  answers null. That is not a stub: it is the SAME answer a real device
     *  gives for any non-HDR image, which is what the two callers are actually
     *  asking about. setGainmap() is kept because it is the only way the field
     *  can become non-null, so an app that sets one can read it back; we do not
     *  tone-map at draw time, which Gainmap.java's own header already says.
     *
     *  AOSP re-extracts from the native bitmap when the Java field is null; we
     *  have no native side, so the field IS the storage and the extra hop
     *  collapses. checkRecycled comes first in all three, as it does upstream. */
    public boolean hasGainmap() {
        checkRecycled("Bitmap is recycled");
        return mGainmap != null;
    }
    public Gainmap getGainmap() {
        checkRecycled("Bitmap is recycled");
        return mGainmap;
    }
    public void setGainmap(Gainmap gainmap) {
        checkRecycled("Bitmap is recycled");
        mGainmap = gainmap;
    }
    private Gainmap mGainmap;

    /** AOSP Bitmap.eraseColor (Bitmap.java:1852-1859): "@throws
     *  IllegalStateException if the bitmap is not mutable." Both guards were
     *  missing, so erasing a recycled or immutable bitmap silently succeeded
     *  where the emulator throws (BMP.state.erase.immutable,
     *  BMP.recycle.eraseColor). */
    public void eraseColor(int color) {
        checkRecycled("Can't erase a recycled bitmap");
        if (!isMutable()) {
            throw new IllegalStateException("cannot erase immutable bitmaps");
        }
        macapkEraseRaw(color);
    }
    /** The store half of eraseColor, without the argument checks -- used by the
     *  createBitmap(hasAlpha=false) path, which erases a bitmap it has not yet
     *  handed to anyone. */
    private void macapkEraseRaw(int color) {
        color = macapkQuantizeOnStore(color);
        if (argbBuffer == null && width > 0 && height > 0) {
            argbBuffer = new int[width * height];
        }
        if (argbBuffer != null) java.util.Arrays.fill(argbBuffer, color);
        if (awtImage != null) {
            java.awt.Graphics2D g = awtImage.createGraphics();
            g.setComposite(java.awt.AlphaComposite.Src);
            g.setColor(new java.awt.Color(color, true));
            g.fillRect(0, 0, awtImage.getWidth(), awtImage.getHeight());
            g.dispose();
        }
        macapkNotifyPixelsChanged();   // SkBitmap::erase notifies (see getGenerationId)
    }

    /** The ARGB value a ZEROED backing store reads as, for this Config.
     *
     *  This is the OTHER half of the createBitmap contract above, and the half
     *  we used to be missing. AOSP erases only two configs by hand and then says
     *  why it does not touch the rest (android-11.0.0_r48
     *  graphics/java/android/graphics/Bitmap.java, createBitmap(display, w, h,
     *  config, hasAlpha, colorSpace)):
     *
     *      bm.setHasAlpha(hasAlpha);
     *      if ((config == Config.ARGB_8888 || config == Config.RGBA_F16) && !hasAlpha) {
     *          nativeErase(bm.mNativePtr, 0xff000000);
     *      }
     *      // No need to initialize the bitmap to zeroes with other configs;
     *      // it is backed by a VM byte array which is by definition preinitialized
     *      // to all zeroes.
     *
     *  "Preinitialized to all zeroes" is only the same thing as "transparent"
     *  for a config that HAS an alpha channel. RGB_565 does not have one -- Skia
     *  types it kOpaque_SkAlphaType unconditionally (which is the same fact
     *  macapkValidateAlphaType above already models for hasAlpha()) -- so a
     *  zeroed 16-bit 565 pixel is r=0 g=0 b=0 with no alpha to be zero, i.e.
     *  OPAQUE BLACK. Blitting a freshly created 565 bitmap paints black on a
     *  real device.
     *
     *  Our backing store is always an int[] of full-precision ARGB (see this
     *  file's header), where a zeroed element means TRANSPARENT. So the two
     *  paths disagreed: macapkQuantizeForConfig ORs 0xff000000 back on for
     *  RGB_565, and the READ was right -- getPixel on a fresh 565 bitmap
     *  correctly answered 0xff000000 -- while Canvas draws from
     *  macapkArgbBuffer() at full precision, where the same pixel is
     *  0x00000000, so the BLIT was a no-op. MEASURED before the fix: a fresh
     *  565 bitmap blitted over 0xff828282 left 0xff828282, where AOSP paints
     *  0xff000000. Found via SolitaireCG, whose splash screen blits a board
     *  bitmap it has not drawn into yet and so showed grey where a device shows
     *  black.
     *
     *  So the zero-state belongs to the ALLOCATION, not to one reader: every
     *  path that materializes the int[] starts it at this value, which makes
     *  read and blit agree by construction.
     *
     *  RGB_565 is the only Config we can name where this is non-zero. ALPHA_8's
     *  zeroed pixel is alpha 0 (transparent, and macapkQuantizeForConfig already
     *  answers 0x00000000 for it); ARGB_4444, ARGB_8888 and RGBA_F16 all carry
     *  an alpha channel; HARDWARE cannot be allocated. The !hasAlpha erase for
     *  8888/F16 stays where AOSP puts it -- in createBitmap -- because there it
     *  is a real erase of a config that CAN be transparent, not a zero-state. */
    private int macapkZeroPixel() {
        return (config == Config.RGB_565) ? 0xff000000 : 0x00000000;
    }
    /** Allocate the int[] backing store in its Config's zero-state. */
    private int[] macapkAllocBuffer() {
        int[] a = new int[Math.max(0, width * height)];
        int z = macapkZeroPixel();
        if (z != 0) java.util.Arrays.fill(a, z);
        return a;
    }

    /** ⚠ ANDROID HAS TWO DIFFERENT 8-to-565 STORE RULES, and this models the
     *  second one. Do NOT "unify" it with macapkQuantizeForConfig below.
     *
     *   - The DECODER stores through SkSwizzler / SkPack888ToRGB16, whose
     *     SkR32ToR16(r) is `r >> 3`: it TRUNCATES. macapkQuantizeForConfig
     *     reproduces that on read and BMP.lossy.q565.decoded agrees with the
     *     emulator today, which is what pins the decoder as truncating.
     *   - eraseColor stores through bitmapErase, which builds an SkColor4f
     *     (libs/hwui/jni/Bitmap.cpp:485-490) and paints it with kSrc, i.e. the
     *     float raster pipeline's store_565 -- `to_unorm(g, 63)` =
     *     round(clamp(g,0,1) * 63), ties to even (external/skia @
     *     android11-release, src/opts/SkRasterPipeline_opts.h:211, :1264-1268).
     *     It ROUNDS.
     *
     *  Green 0xC0 is where the two part company: 192/255*63 = 47.435, so the
     *  decoder keeps 48 (reads back 0xC3) and eraseColor keeps 47 (reads back
     *  0xBE). BMP.lossy.q565.bl measures the eraseColor path and wants 0xBE
     *  (ref ff00beff); we answered ff00c3ff because our store kept the caller's
     *  full-precision colour and deferred ALL loss to the truncating read.
     *
     *  So model the STORE. The value written back out is the expanded 565
     *  colour, which the read-time truncation is IDEMPOTENT on (MEASURED over
     *  all 256 greys), so macapkQuantizeForConfig stays exactly as it is and the
     *  decode path keeps its correct truncation.
     *
     *  Scoped to RGB_565 on purpose. ARGB_4444 would round in Skia too, but API
     *  30 quietly upgrades a 4444 request to ARGB_8888 (BMP.create.cfg.4444
     *  answers cfg=ARGB_8888 on the emulator), so there is no 4444 store to be
     *  faithful to. ALPHA_8 has no colour to quantise. */
    private int macapkQuantizeOnStore(int argb) {
        if (config != Config.RGB_565) return argb;
        int r5 = macapkToUnorm((argb >> 16) & 0xff, 31);
        int g6 = macapkToUnorm((argb >>  8) & 0xff, 63);
        int b5 = macapkToUnorm( argb        & 0xff, 31);
        // SkR16ToR32's bit replication, the same expansion the read uses.
        return (argb & 0xff000000) | (((r5 << 3) | (r5 >> 2)) << 16)
                                   | (((g6 << 2) | (g6 >> 4)) << 8)
                                   |  ((b5 << 3) | (b5 >> 2));
    }
    /** Skia's to_unorm(v, scale) for an 8-bit channel: round(clamp(v,0,1)*scale)
     *  with ties to even. MEASURED: for scale 31 and 63 the exact float
     *  formulation is irrelevant (c*(1/255f)*scale, c*scale/255f and the double
     *  form agree on all 256 inputs), so the simple form is safe here. */
    private static int macapkToUnorm(int c8, int scale) {
        int v = (int) Math.rint((double) (c8 * (1f / 255f) * scale));
        return v < 0 ? 0 : (v > scale ? scale : v);
    }
    /** AOSP API-29 wide-gamut overload. `(int) color` is WRONG: an sRGB
     *  ColorLong is `(argb & 0xffffffffL) << 32` (Color.pack), so the low 32
     *  bits are ZERO and every eraseColor(Color.pack(c)) painted transparent
     *  black. MEASURED (BMP.state.erase.longColour): the emulator answers
     *  ffff8000, we answered 00000000. Color.toArgb(long) is the documented
     *  inverse and handles the non-sRGB spaces too. */
    public void eraseColor(long color) {
        checkRecycled("Can't erase a recycled bitmap");
        if (!isMutable()) {
            throw new IllegalStateException("cannot erase immutable bitmaps");
        }
        macapkEraseRaw(android.graphics.Color.toArgb(color));
    }
    /** AOSP Bitmap.createScaledBitmap(src, dstW, dstH, filter): a new
     *  bitmap holding src scaled to dstW x dstH. Apps (and the surfaces
     *  our SurfaceView overlay driver hands back to their
     *  SurfaceHolder.Callback.surfaceChanged) scale UI/atlas bitmaps
     *  through this; the method was missing -> NoSuchMethodError flooding
     *  the overlay path (Subway/Crossy/DumbWays). We resample whatever
     *  Java-side pixels the source has (argbBuffer, else the AWT backing);
     *  a plain reference bitmap (pixels live native) yields a correctly
     *  sized destination so the caller proceeds. Never returns null. */
    /** ⚠ AOSP's createScaledBitmap is a THIN WRAPPER, not an independent
     *  resampler (android-11.0.0_r48 Bitmap.java:796-810): it builds a scale
     *  Matrix and calls createBitmap(src, 0, 0, w, h, m, filter). Our
     *  standalone version got four observable answers wrong because of that,
     *  and every one of them is inherited for free by delegating:
     *   - a MUTABLE source scaled to its own size returned THE SAME OBJECT.
     *     The identity short-circuit lives in createBitmap and is guarded on
     *     IMMUTABILITY, so a mutable source must be copied
     *     (BMP.scaled.same.mutableCopies: emulator 0, we answered 1).
     *   - a zero destination was silently clamped to 1 instead of reaching the
     *     zero-sized createBitmap that throws (BMP.scaled.err.zeroWidth).
     *   - a NEGATIVE destination is not an error at all: it is a FLIP, and the
     *     mapped rect's width is its magnitude, so (-8, 16) is a legal 8x16
     *     bitmap (BMP.scaled.err.negWidth: emulator 8x16, we answered 1x16).
     *   - `filter` was ignored, so a filtered upscale produced the source's
     *     four colours instead of a blended edge
     *     (BMP.scaled.up2x.filtered.ncolours).
     *  It also inherits config preservation (a scaled 565 stays 565) and the
     *  recycled-source IllegalArgumentException. */
    public static Bitmap createScaledBitmap(Bitmap src, int dstWidth,
                                            int dstHeight, boolean filter) {
        android.graphics.Matrix m = new android.graphics.Matrix();
        final int width = src.getWidth();
        final int height = src.getHeight();
        if (width != dstWidth || height != dstHeight) {
            m.setScale(dstWidth / (float) width, dstHeight / (float) height);
        }
        return createBitmap(src, 0, 0, width, height, m, filter);
    }

    // ---- AOSP createBitmap(source-Bitmap, ...) family ----
    // Crop/transform a region of an existing bitmap into a new one. Used
    // everywhere apps slice atlases, crop photos, or rotate thumbnails
    // (Glide TransformationUtils, ExifInterface rotate, custom croppers).
    // These overloads were missing -> NoSuchMethodError at link time for
    // any of those callers. We resample whatever Java-side pixels the
    // source has (argbBuffer, else the AWT backing) so the new bitmap holds
    // correct pixels. The Matrix overload applies the affine transform via
    // the same path Canvas uses (AffineTransform on a BufferedImage).

    public static Bitmap createBitmap(Bitmap src) {
        if (src == null) return createBitmap(1, 1, Config.ARGB_8888);
        return createBitmap(src, 0, 0, src.width, src.height);
    }

    public static Bitmap createBitmap(Bitmap source, int x, int y, int width, int height) {
        return createBitmap(source, x, y, width, height, null, false);
    }

    /** AOSP createBitmap(source, x, y, w, h, m, filter)
     *  (android-11.0.0_r48 Bitmap.java:870-985), reproduced in AOSP's own
     *  order. Four contracts were missing:
     *   1. THE ARGUMENT CHECKS, and their ORDER: checkXYSign, then
     *      checkWidthHeight, then x+width, then y+height, then the recycled
     *      source. We silently clamped instead, so createBitmap(src,-1,0,4,4)
     *      returned a 4x4 bitmap of garbage where the emulator throws
     *      (BMP.create.err.negX / negY / zeroWidth / zeroHeight / xPlusWidth /
     *      yPlusHeight / negXZeroWidth / recycledSource).
     *   2. THE IDENTITY SHORT-CIRCUIT: "If the source bitmap is immutable and
     *      the requested subset is the same as the source bitmap itself, then
     *      the source bitmap is returned and no new bitmap is created"
     *      (:857-861). Guarded on `!source.isMutable()`, and the matrix test is
     *      `m == null || m.isIdentity()` -- an explicit identity Matrix
     *      qualifies too (BMP.create.identity.immutableFull /
     *      identity.identityMatrix).
     *   3. THE CONFIG MAPPING (:922-940): RGB_565, ALPHA_8 and RGBA_F16 are
     *      PRESERVED into the result, ARGB_4444 is folded into ARGB_8888. We
     *      hardcoded ARGB_8888, which doubles the memory of every cropped 565
     *      bitmap and changes what the app measures (BMP.create.cfg.565 /
     *      cfg.alpha8 / cfg.f16).
     *   4. THE rectStaysRect RULE (:940-964): a transform that is NOT a
     *      multiple of 90 degrees turns antialiasing ON and forces ARGB_8888,
     *      because the antialiased edge needs an alpha channel the source
     *      config may not have. Preserving 565 through a 45-degree rotation
     *      draws a black fringe on every rotated ImageView
     *      (BMP.create.m.rotate45.565 vs m.rotate90.565 -- the pair is the
     *      whole question). */
    public static Bitmap createBitmap(Bitmap source, int x, int y, int width, int height,
                                      android.graphics.Matrix m, boolean filter) {
        checkXYSign(x, y);
        checkWidthHeight(width, height);
        if (x + width > source.getWidth()) {
            throw new IllegalArgumentException("x + width must be <= bitmap.width()");
        }
        if (y + height > source.getHeight()) {
            throw new IllegalArgumentException("y + height must be <= bitmap.height()");
        }
        if (source.isRecycled()) {
            throw new IllegalArgumentException("cannot use a recycled source in createBitmap");
        }
        // check if we can just return our argument unchanged
        if (!source.isMutable() && x == 0 && y == 0 && width == source.getWidth()
                && height == source.getHeight() && (m == null || m.isIdentity())) {
            return source;
        }

        // AOSP's newConfig switch. GIF files generate null configs; assume 8888.
        Config newConfig = Config.ARGB_8888;
        final Config srcConfig = source.getConfig();
        if (srcConfig == Config.RGB_565 || srcConfig == Config.ALPHA_8
                || srcConfig == Config.RGBA_F16) {
            newConfig = srcConfig;
        }

        // Pull the source region into an ARGB int[].
        int[] srcPix = source.macapkArgbBuffer();
        int[] region = new int[width * height];
        if (srcPix != null && source.width > 0 && source.height > 0) {
            for (int row = 0; row < height; row++) {
                int sy = y + row;
                if (sy < 0 || sy >= source.height) continue;
                int srow = sy * source.width;
                int drow = row * width;
                for (int col = 0; col < width; col++) {
                    int sx = x + col;
                    if (sx < 0 || sx >= source.width) continue;
                    region[drow + col] = srcPix[srow + sx];
                }
            }
        }
        // No matrix (or identity) -> the cropped region IS the result.
        if (m == null || m.isIdentity()) {
            Bitmap out = createBitmap(null, width, height, newConfig,
                    source.hasAlpha(), macapkDefaultColorSpace(newConfig));
            // AOSP: "The new bitmap was created from a known bitmap source so
            // assume that they use the same density".
            out.density = source.density;
            out.setHasAlpha(source.hasAlpha());
            out.setPremultiplied(source.requestPremultiplied);
            out.macapkSetArgbBuffer(region);
            return out;
        }
        // AOSP: `final boolean transformed = !m.rectStaysRect();` -- true for
        // any rotation that is not a multiple of 90 degrees (and for a skew).
        final boolean transformed = !m.rectStaysRect();
        Config transformedConfig = newConfig;
        if (transformed && transformedConfig != Config.ARGB_8888
                && transformedConfig != Config.RGBA_F16) {
            transformedConfig = Config.ARGB_8888;
        }
        java.awt.geom.AffineTransform at = m.macapkToAffine();
        // AOSP maps the DESTINATION rect (0,0,w,h) and rounds each dimension:
        // `neww = Math.round(deviceR.width())`. No max(1,...): a mapped width
        // that rounds to 0 reaches createBitmap and throws, which is the
        // documented answer for createScaledBitmap(src, 0, h).
        java.awt.geom.Rectangle2D bounds = at.createTransformedShape(
                new java.awt.geom.Rectangle2D.Float(0, 0, width, height)).getBounds2D();
        int dw = Math.round((float) bounds.getWidth());
        int dh = Math.round((float) bounds.getHeight());
        Bitmap out = createBitmap(null, dw, dh, transformedConfig,
                transformed || source.hasAlpha(), macapkDefaultColorSpace(transformedConfig));
        // Translate so the (possibly negative) transformed origin maps to 0,0.
        java.awt.geom.AffineTransform full = new java.awt.geom.AffineTransform();
        full.translate(-bounds.getX(), -bounds.getY());
        full.concatenate(at);
        // This used to be g.drawImage(srcImg, full, null) with Java2D's
        // BILINEAR hint (and, for `transformed`, its ANTIALIASING hint). Java2D
        // is not Skia in either respect and BOTH showed up as probe rows -- see
        // macapkResample, which replaces it.
        BufferedImage dstImg = new BufferedImage(dw, dh, BufferedImage.TYPE_INT_ARGB);
        if (dw > 0 && dh > 0) {
            int[] resampled = macapkResample(region, width, height, dw, dh,
                    full, filter, transformed);
            dstImg.setRGB(0, 0, dw, dh, resampled, 0, dw);
        }
        out.density = source.density;
        out.setHasAlpha(source.hasAlpha());
        out.setPremultiplied(source.requestPremultiplied);
        out.macapkSetAwtImage(dstImg);
        return out;
    }

    /** Skia's bitmap sampler, hand-written, because Java2D's is a DIFFERENT
     *  sampler and three probe rows measured the difference. Inverse-maps every
     *  destination pixel centre and samples the source; returns dw*dh ARGB.
     *
     *  WHY NOT g.drawImage. Three separate divergences, all in one call:
     *
     *  1. THE LERP WEIGHT IS 4 BITS, AND TRUNCATED. Skia's legacy sampler packs
     *     the sub-texel position as the top four fractional bits of the 16.16
     *     coordinate -- `return (fx >> 12) & 0xf` (external/skia @
     *     android11-release, src/core/SkBitmapProcState_matrixProcs.cpp:143-148,
     *     extract_low_bits_clamp_clamp) -- so a weight of 1/6 becomes 13/16,
     *     not 0.8333. Java2D lerps at full precision. MEASURED
     *     (BMP.lossy.up2x.filter, BMP.lossy.resize.up15x): the two disagree by
     *     1 and by 3-4 per channel respectively.
     *  2. THE 4-WAY BLEND DIVIDES BY 256 WITH A SHIFT, i.e. truncates
     *     (Filter_32_opaque, src/core/SkBitmapProcState_filter.h). Java2D rounds.
     *  3. drawImage CANNOT ANTIALIAS AN IMAGE EDGE. KEY_ANTIALIASING governs
     *     SHAPE rendering only, so the rotate-45 path set the hint and got hard
     *     edges anyway: 5 colours where Android answers 23
     *     (BMP.lossy.rotate45.hist). AOSP asks for it explicitly --
     *     `if (transformed) { paint.setAntiAlias(true); }`,
     *     frameworks/base graphics/java/android/graphics/Bitmap.java:952-959 --
     *     and Skia honours it on the image's own boundary. We recover it by
     *     rasterising the transformed source RECT (Java2D antialiases a SHAPE
     *     perfectly well) into a coverage mask and scaling the sampled alpha by
     *     it. ⚠ Masking drawImage's OUTPUT does not work and is not worth
     *     re-trying: drawImage never writes the partially covered boundary
     *     pixels at all, so there is nothing there to attenuate. The sampler has
     *     to run over the MASK's support.
     *
     *  MEASURED, standalone, against the emulator's own answers: this reproduces
     *  up2x.filter, resize.up15x and rotate45.hist (n=23, transparent=231)
     *  byte-for-byte, and leaves all eleven passing corner rows
     *  (create.m.scale2 / scaleHalf / scaleAsym / rotate90 / flipX / flipY /
     *  filterOn, scaled.up3x / down2x / down4x / to1x1) unchanged.
     *
     *  COST: a 2048x2048 -> 4096x4096 filtered resample is 211ms against
     *  Java2D's 139ms on this host. 1.5x on 16 megapixels of output is the price
     *  of the fidelity; there is no cliff to guard against.
     *
     *  ⚠ SCOPE. Canvas.drawBitmap deliberately does NOT route here. A general
     *  Canvas carrying a colour space can take Skia's FLOAT raster-pipeline
     *  bilinear instead (full-precision weights, rounded), and no probe row
     *  currently pins which one it gets. Add a case before generalising. */
    static int[] macapkResample(int[] src, int sw, int sh, int dw, int dh,
                                java.awt.geom.AffineTransform full,
                                boolean filter, boolean antiAlias) {
        int[] out = new int[dw * dh];
        if (sw <= 0 || sh <= 0) return out;
        java.awt.geom.AffineTransform inv;
        try {
            inv = full.createInverse();
        } catch (java.awt.geom.NoninvertibleTransformException e) {
            // A degenerate matrix collapses the image to nothing, which is what
            // Skia draws too. Transparent is the honest answer.
            return out;
        }

        // The coverage mask. Only built for a transform that lost rectStaysRect:
        // an axis-aligned map covers every destination pixel completely (dw is
        // Math.round of the mapped width, so the last centre at dw-0.5 can never
        // fall outside a rect at least dw-0.5 wide), and a full-coverage mask
        // would be pure cost.
        int[] cov = null;
        if (antiAlias) {
            BufferedImage mask = new BufferedImage(dw, dh, BufferedImage.TYPE_INT_ARGB);
            java.awt.Graphics2D mg = mask.createGraphics();
            mg.setRenderingHint(java.awt.RenderingHints.KEY_ANTIALIASING,
                    java.awt.RenderingHints.VALUE_ANTIALIAS_ON);
            mg.setColor(java.awt.Color.WHITE);
            mg.fill(full.createTransformedShape(
                    new java.awt.geom.Rectangle2D.Float(0, 0, sw, sh)));
            mg.dispose();
            cov = new int[dw * dh];
            mask.getRGB(0, 0, dw, dh, cov, 0, dw);
        }

        final int maxX = sw - 1, maxY = sh - 1;
        double[] pt = new double[2];
        for (int dy = 0; dy < dh; dy++) {
            for (int dx = 0; dx < dw; dx++) {
                int c = 255;
                if (cov != null) {
                    c = cov[dy * dw + dx] >>> 24;
                    if (c == 0) continue;             // outside the image: transparent
                }
                // SkBitmapProcStateAutoMapper inverse-maps the DEVICE pixel
                // CENTRE (SkBitmapProcState.h:163-190).
                pt[0] = dx + 0.5;
                pt[1] = dy + 0.5;
                inv.transform(pt, 0, pt, 0, 1);
                int argb;
                if (filter) {
                    long fx = macapkFilterCoord((float) pt[0]);
                    long fy = macapkFilterCoord((float) pt[1]);
                    // pack<clamp, extract_low_bits_clamp_clamp>
                    // (SkBitmapProcState_matrixProcs.cpp:156-162): the low
                    // texel, the 4-bit weight, and the high texel one SK_Fixed1
                    // further on -- each coordinate independently clamped.
                    int x0 = macapkPin((int) (fx >> 16), maxX);
                    int x1 = macapkPin((int) ((fx + 0x10000L) >> 16), maxX);
                    int y0 = macapkPin((int) (fy >> 16), maxY);
                    int y1 = macapkPin((int) ((fy + 0x10000L) >> 16), maxY);
                    argb = macapkFilter32((int) ((fx >> 12) & 0xf), (int) ((fy >> 12) & 0xf),
                            src[y0 * sw + x0], src[y0 * sw + x1],
                            src[y1 * sw + x0], src[y1 * sw + x1]);
                } else {
                    int x0 = macapkPin((int) (macapkNearestCoord((float) pt[0]) >> 16), maxX);
                    int y0 = macapkPin((int) (macapkNearestCoord((float) pt[1]) >> 16), maxY);
                    argb = src[y0 * sw + x0];
                }
                if (c != 255) {
                    // Our backing store is NON-premultiplied end to end (see this
                    // file's header), so coverage attenuates ALPHA only. Scaling
                    // rgb as well would be the premultiplied form and would read
                    // back dark.
                    argb = (macapkMulDiv255Round(argb >>> 24, c) << 24) | (argb & 0x00ffffff);
                }
                out[dy * dw + dx] = argb;
            }
        }
        return out;
    }
    /** The FILTERED coordinate: 16.16 fixed, biased by half a texel.
     *  fFilterOneX is SK_Fixed1 for clamp tiling
     *  (SkBitmapProcState_matrixProcs.cpp:547) and the mapper subtracts
     *  `fFilterOneX >> 1`, which is the standard bilerp -1/2 shift.
     *
     *  The 1<<48 scale is not decoration: SkFractionalInt is 16.48, so the bias
     *  is subtracted THERE and the result is shifted down to 16.16 with an
     *  ARITHMETIC shift, which FLOORS. Doing it in 16.16 directly would truncate
     *  toward zero instead, and the two differ for a negative coordinate, i.e.
     *  exactly at the left/top border where a rotated image needs it.
     *
     *  ⚠ Skia's filter_scale walks the row by ADDING a fixed dx per pixel rather
     *  than re-mapping each centre. Re-mapping is used here because it is also
     *  correct for the affine branch, and MEASURED it makes no difference: it
     *  reproduces the emulator exactly at both 16->32 and 24->36. */
    private static long macapkFilterCoord(float pt) {
        return (((long) ((double) pt * 281474976710656.0)) - (0x8000L << 32)) >> 32;
    }
    /** The NEAREST coordinate. Skia biases by ONE SkFixed unit, not half a
     *  texel: "SkFixed epsilon bias to ensure inverse-mapped bitmap coordinates
     *  are rounded consistently WRT geometry ... We scale it to persist
     *  SkFractionalInt -> SkFixed conversions", `biasX =
     *  (s.fInvMatrix.getScaleX() > 0)` (SkBitmapProcState.h:163-190). Without it
     *  createScaledBitmap(src, 1, 1, false) on a 16x16 source samples exactly
     *  8.0, the quadrant boundary, and flooring picks the bottom-right texel
     *  where Android picks the top-left (BMP.scaled.to1x1.px).
     *
     *  This replaces the `full.translate(1/65536, 1/65536)` that used to sit at
     *  the call site: same intent, but applied to the SOURCE coordinate where
     *  Skia applies it rather than smuggled through the transform, and now it
     *  cannot interact with the filtered branch (which needs a different bias). */
    private static long macapkNearestCoord(float pt) {
        return (((long) ((double) pt * 281474976710656.0)) - (1L << 32)) >> 32;
    }
    private static int macapkPin(int v, int max) {
        return v < 0 ? 0 : (v > max ? max : v);
    }
    /** Filter_32_opaque, verbatim (src/core/SkBitmapProcState_filter.h). x and y
     *  are the 4-bit weights, so the sixteen partial products sum to 256 and the
     *  final `>> 8` is the divide -- truncating, which is half the divergence
     *  this whole sampler exists to reproduce. Two channels ride in `lo` and two
     *  in `hi` so one multiply covers a pair. */
    private static int macapkFilter32(int x, int y, int a00, int a01, int a10, int a11) {
        int xy = x * y;
        final int mask = 0xFF00FF;
        int scale = 256 - 16 * y - 16 * x + xy;
        int lo = (a00 & mask) * scale;
        int hi = ((a00 >>> 8) & mask) * scale;
        scale = 16 * x - xy;
        lo += (a01 & mask) * scale;
        hi += ((a01 >>> 8) & mask) * scale;
        scale = 16 * y - xy;
        lo += (a10 & mask) * scale;
        hi += ((a10 >>> 8) & mask) * scale;
        lo += (a11 & mask) * xy;
        hi += ((a11 >>> 8) & mask) * xy;
        return ((lo >>> 8) & mask) | (hi & ~mask);
    }

    // ---- AOSP createBitmap(int[] colors, ...) family ----
    // Build a bitmap directly from an ARGB pixel array. Used by code that
    // synthesizes images in memory (custom gradients, QR generators,
    // Glide's GifDecoder frame assembly). Missing -> NoSuchMethodError.
    public static Bitmap createBitmap(int[] colors, int width, int height, Config config) {
        return createBitmap(colors, 0, width, width, height, config);
    }

    /** ⚠ THE RESULT IS IMMUTABLE. AOSP builds it with
     *      nativeCreate(colors, offset, stride, width, height, config.nativeInt,
     *                   false, sRGB.getNativeInstance());
     *  (android-11.0.0_r48 Bitmap.java:2257-2282) where that `false` is
     *  isMutable -- unlike createBitmap(w, h, config), which passes true. We
     *  inherited mutable=true from the delegate. MEASURED
     *  (RAS.bmpapi.fromColors.isMutable): 0 on the emulator, 1 here. */
    public static Bitmap createBitmap(int[] colors, int offset, int stride,
                                      int width, int height, Config config) {
        // AOSP's own check ORDER (Bitmap.java:1184-1210), which is an answer:
        // dimensions FIRST, then abs(stride) < width, then the lastScanline
        // algebra -- so a zero width reports the DIMENSION failure while a bad
        // stride with a legal width reports the stride one, and an array that
        // is one element short is ArrayIndexOutOfBoundsException and NOT
        // IllegalArgumentException. We validated nothing and silently skipped
        // out-of-range indices, so every one of these produced a plausible,
        // wrong bitmap (BMP.create.fc.zeroWidth / strideTooSmall / arrayShort /
        // negOffset).
        checkWidthHeight(width, height);
        if (Math.abs(stride) < width) {
            throw new IllegalArgumentException("abs(stride) must be >= width");
        }
        int lastScanline = offset + (height - 1) * stride;
        int length = colors.length;
        if (offset < 0 || (offset + width > length) || lastScanline < 0
                || (lastScanline + width > length)) {
            throw new ArrayIndexOutOfBoundsException();
        }
        Bitmap b = createBitmap(width, height, config);
        b.mutable = false;
        if (colors != null && width > 0 && height > 0) {
            int[] out = new int[width * height];
            for (int row = 0; row < height; row++) {
                int src = offset + row * stride;
                int dst = row * width;
                for (int col = 0; col < width; col++) {
                    int idx = src + col;
                    if (idx >= 0 && idx < colors.length) out[dst + col] = colors[idx];
                }
            }
            b.macapkSetArgbBuffer(out);
        }
        return b;
    }
    public static Bitmap createBitmap(android.util.DisplayMetrics dm, int[] colors,
                                      int width, int height, Config config) {
        return macapkWithDensity(createBitmap(colors, 0, width, width, height, config), dm);
    }
    public static Bitmap createBitmap(android.util.DisplayMetrics dm, int[] colors,
                                      int offset, int stride, int width, int height,
                                      Config config) {
        return macapkWithDensity(
                createBitmap(colors, offset, stride, width, height, config), dm);
    }

    // ---- AOSP createBitmap(Picture, ...) family ----
    // Rasterize a recorded Picture into a Bitmap. Compose / RenderNode
    // software fallbacks and drawable caches record into a Picture and then
    // ask for it as a Bitmap; the overloads were missing entirely, so those
    // call sites hit NoSuchMethodError.

    /** AOSP: "Creates a Bitmap from the given Picture source ... with width and
     *  height the same as the Picture's width and height and a Config.HARDWARE
     *  config." */
    public static Bitmap createBitmap(Picture source) {
        return createBitmap(source, source.getWidth(), source.getHeight(), Config.HARDWARE);
    }

    /** AOSP: "The bitmap will be immutable with the given width and height. If
     *  the width and height are not the same as the Picture's width & height,
     *  the Picture will be scaled to fit." */
    public static Bitmap createBitmap(Picture source, int width, int height, Config config) {
        if (width <= 0 || height <= 0) {
            throw new IllegalArgumentException("width & height must be > 0");
        }
        if (config == null) {
            throw new IllegalArgumentException("Config must not be null");
        }
        source.endRecording();
        // AOSP takes a RenderNode/ThreadedRenderer path when the requested
        // config is HARDWARE and a plain software Canvas path otherwise, and
        // the hardware path ends with `bitmap.copy(config, false)` for a
        // non-HARDWARE request -- so the rasterized pixels are identical
        // either way. We have no GPU-resident bitmaps, so the software Canvas
        // is the only path. Config.HARDWARE cannot back a createBitmap
        // allocation (it throws, as on AOSP), hence allocate 8888 and retag.
        Bitmap bitmap = createBitmap(width, height,
                config == Config.HARDWARE ? Config.ARGB_8888 : config);
        Canvas canvas = new Canvas(bitmap);
        // Guard the divide: a Picture that never recorded reports 0x0, and an
        // infinite scale would poison the transform rather than draw nothing.
        if (source.getWidth() > 0 && source.getHeight() > 0
                && (source.getWidth() != width || source.getHeight() != height)) {
            canvas.scale(width / (float) source.getWidth(),
                         height / (float) source.getHeight());
        }
        canvas.drawPicture(source);
        canvas.setBitmap(null);
        bitmap.config = config;
        bitmap.mutable = false;   // AOSP: bitmap.setImmutable()
        return bitmap;
    }

    /** AWT-backed bitmap for Canvas.drawText callers (Cocos2dxBitmap).
     *  Allocates a real ARGB BufferedImage that Canvas / Graphics2D
     *  can paint into. */
    public static Bitmap createBitmapWithCanvasBacking(int w, int h) {
        Bitmap b = new Bitmap();
        b.width = w; b.height = h; b.config = Config.ARGB_8888;
        if (w > 0 && h > 0) {
            b.awtImage = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
        }
        b.mutable = true;   // a Canvas drawing target is mutable
        b.allocationByteCount = b.getByteCount();  // the TRUE, fixed allocation
        return b;
    }
    public int  getWidth()  { return width; }
    public int  getHeight() { return height; }
    public Config getConfig() { return config; }

    /** AOSP Bitmap.getHardwareBuffer (API 31). Mac.apk bitmaps are software-
     *  backed, so this returns a DESCRIPTOR of the bitmap (format + size), which
     *  satisfies callers that query it; it is not a mappable GPU buffer. */
    public android.hardware.HardwareBuffer getHardwareBuffer() {
        int fmt;
        Config c = config != null ? config : Config.ARGB_8888;
        switch (c) {
            case RGB_565:  fmt = android.hardware.HardwareBuffer.RGB_565; break;
            case RGBA_F16: fmt = android.hardware.HardwareBuffer.RGBA_FP16; break;
            default:       fmt = android.hardware.HardwareBuffer.RGBA_8888; break;
        }
        return android.hardware.HardwareBuffer.create(Math.max(1, width), Math.max(1, height),
                fmt, 1, android.hardware.HardwareBuffer.USAGE_GPU_SAMPLED_IMAGE);
    }
    /** AOSP Bitmap.wrapHardwareBuffer (API 29): a Bitmap over a HardwareBuffer.
     *  Mac.apk has no GPU buffer to alias, so this materializes a same-size
     *  software Bitmap. */
    public static Bitmap wrapHardwareBuffer(android.hardware.HardwareBuffer hardwareBuffer,
            android.graphics.ColorSpace colorSpace) {
        if (hardwareBuffer == null) return null;
        return createBitmap(Math.max(1, hardwareBuffer.getWidth()),
                Math.max(1, hardwareBuffer.getHeight()), Config.ARGB_8888);
    }
    /** AOSP: a HARDWARE bitmap's pixels live in graphics memory and can never
     *  be written, so it is immutable regardless of how it was produced. */
    public boolean isMutable() { return mutable && config != Config.HARDWARE; }
    /** AOSP: "Returns true if the bitmap's config supports per-pixel alpha, and
     *  if the pixels may contain non-opaque alpha values." Both halves come out
     *  of the single alpha type: an RGB_565 bitmap is structurally opaque, and
     *  an ARGB_8888 one the app flagged opaque via setHasAlpha(false) reports
     *  false too. Deliberately NOT guarded by checkRecycled -- AOSP only logs
     *  ("Called hasAlpha() on a recycle()'d bitmap"), so the answer survives
     *  (BMP.recycle.hasAlpha). */
    public boolean hasAlpha()  { return alphaType != ALPHA_OPAQUE; }
    /** AOSP setHasAlpha(v) is `nativeSetHasAlpha(ptr, v, mRequestPremultiplied)`
     *  -- note the SECOND argument. Turning alpha back ON restores the app's
     *  last premultiplication REQUEST, it does not force premultiplied; that is
     *  why setPremultiplied(false) followed by setHasAlpha(true) leaves
     *  isPremultiplied() false (BMP.state.premul.thenHasAlpha). */
    public void setHasAlpha(boolean v) {
        checkRecycled("setHasAlpha called on a recycled bitmap");
        final int was = alphaType;
        macapkSetAlphaType(v ? (requestPremultiplied ? ALPHA_PREMUL : ALPHA_UNPREMUL)
                             : ALPHA_OPAQUE);
        // Labelled deviation from hwui (no notify there); see getGenerationId.
        if (alphaType != was) macapkNotifyPixelsChanged();
    }
    /** AOSP Bitmap.checkRecycled: the shared guard behind every "@throws
     *  IllegalStateException if the bitmap has been recycled" in this class. */
    private void checkRecycled(String errorMessage) {
        if (recycled) {
            throw new IllegalStateException(errorMessage);
        }
    }
    /** AOSP checkXYSign / checkWidthHeight / checkPixelAccess /
     *  checkPixelsAccess (Bitmap.java:433-459, :1985-2035). The ORDER is
     *  itself an answer and it is where a reimplementation drifts: the sign
     *  test runs before the upper-bound test, so getPixel(-1, 99) reports the
     *  X SIGN failure; and checkPixelsAccess tests the geometry before the
     *  array algebra, so an x+width past the edge is IllegalArgumentException
     *  even when the destination array is enormous. We previously validated
     *  none of it and silently returned 0 / skipped the row, which is the
     *  failure mode that hides an app's own indexing bug until it renders
     *  garbage. */
    private static void checkXYSign(int x, int y) {
        if (x < 0) throw new IllegalArgumentException("x must be >= 0");
        if (y < 0) throw new IllegalArgumentException("y must be >= 0");
    }
    private static void checkWidthHeight(int width, int height) {
        if (width <= 0) throw new IllegalArgumentException("width must be > 0");
        if (height <= 0) throw new IllegalArgumentException("height must be > 0");
    }
    private void checkPixelAccess(int x, int y) {
        checkXYSign(x, y);
        if (x >= getWidth()) throw new IllegalArgumentException("x must be < bitmap.width()");
        if (y >= getHeight()) throw new IllegalArgumentException("y must be < bitmap.height()");
    }
    private void checkPixelsAccess(int x, int y, int width, int height,
                                   int offset, int stride, int[] pixels) {
        checkXYSign(x, y);
        if (width < 0) throw new IllegalArgumentException("width must be >= 0");
        if (height < 0) throw new IllegalArgumentException("height must be >= 0");
        if (x + width > getWidth()) {
            throw new IllegalArgumentException("x + width must be <= bitmap.width()");
        }
        if (y + height > getHeight()) {
            throw new IllegalArgumentException("y + height must be <= bitmap.height()");
        }
        if (Math.abs(stride) < width) {
            throw new IllegalArgumentException("abs(stride) must be >= width");
        }
        int lastScanline = offset + (height - 1) * stride;
        int length = pixels.length;
        if (offset < 0 || (offset + width > length)
                || lastScanline < 0 || (lastScanline + width > length)) {
            throw new ArrayIndexOutOfBoundsException();
        }
    }
    public int  getDensity()   { return density; }
    public void setDensity(int d) { this.density = d; }
    /** AOSP Bitmap.scaleFromDensity, verbatim: DENSITY_NONE on either side (or
     *  a matching pair) means "leave the size alone", otherwise the size is
     *  rescaled by tdensity/sdensity with round-to-nearest. */
    public static int scaleFromDensity(int size, int sdensity, int tdensity) {
        if (sdensity == DENSITY_NONE || tdensity == DENSITY_NONE || sdensity == tdensity) {
            return size;
        }
        return ((size * tdensity) + (sdensity >> 1)) / sdensity;
    }
    /** AOSP: the width this bitmap should be drawn at when it is scaled from
     *  its own density to targetDensity. Apps size UI off these rather than
     *  off the raw pixel dimensions. */
    public int getScaledWidth(int targetDensity) {
        return scaleFromDensity(getWidth(), density, targetDensity);
    }
    public int getScaledHeight(int targetDensity) {
        return scaleFromDensity(getHeight(), density, targetDensity);
    }
    /** AOSP convenience overloads: scale to the DisplayMetrics' densityDpi. */
    public int getScaledWidth(android.util.DisplayMetrics metrics) {
        return scaleFromDensity(getWidth(), density, metrics.densityDpi);
    }
    public int getScaledHeight(android.util.DisplayMetrics metrics) {
        return scaleFromDensity(getHeight(), density, metrics.densityDpi);
    }
    /** AOSP convenience overloads: scale to the target Canvas' density
     *  (AOSP reads canvas.mDensity; Canvas.getDensity() is our accessor). */
    public int getScaledWidth(Canvas canvas) {
        return scaleFromDensity(getWidth(), density, canvas.getDensity());
    }
    public int getScaledHeight(Canvas canvas) {
        return scaleFromDensity(getHeight(), density, canvas.getDensity());
    }
    /** AOSP hasMipMap/setHasMipMap: an advisory flag stored on the bitmap
     *  telling the renderer to build mipmaps when the bitmap is minified. */
    public boolean hasMipMap() { return hasMipMap; }
    public void setHasMipMap(boolean hasMipMap) { this.hasMipMap = hasMipMap; }
    /** AOSP getNinePatchChunk: the 9-patch chunk the decoder attached, or null
     *  when this bitmap did not come from a .9.png. */
    public byte[] getNinePatchChunk() { return ninePatchChunk; }
    /** Internal, mirrors AOSP's private setNinePatchChunk (called from the
     *  decoder): lets BitmapFactory hand over a chunk it extracted. */
    void setNinePatchChunk(byte[] chunk) { this.ninePatchChunk = chunk; }
    public long getNativeHandle() { return nativeHandle; }
    public void setNativeHandle(long h) { this.nativeHandle = h; }
    /** AOSP Bitmap.recycle(): "Free the native object associated with this
     *  bitmap ... This is an advanced call, and normally need not be called."
     *  It is idempotent (`if (!mRecycled)`), and afterwards the METADATA
     *  survives (width/height/rowBytes/config/density/alpha are cached in
     *  BitmapWrapper::freePixels before the pixels go, Bitmap.cpp:45-53) while
     *  every pixel-touching call throws IllegalStateException. This was an
     *  empty method with `isRecycled()` hardwired false, so an app's
     *  "recycled?" guard never fired and it kept drawing a bitmap it believed
     *  it had freed -- and 20+ documented throws silently succeeded. Dropping
     *  the two backing stores is the point: it is what makes recycle() actually
     *  release memory, which is the only reason an app calls it. */
    public void recycle() {
        synchronized (this) {   // against macapkPixelRef(); see there
            if (recycled) return;
            macapkHandPixelsToRecordings();
            recycled = true;
        }
        argbBuffer = null;
        awtImage = null;
        argbView = null;
        argbViewSrc = null;
    }

    // ---- The pixel ref a display list keeps (Stage A, MacApkDisplayList) ----
    //
    // A recorded bitmap draw holds the Bitmap BY REFERENCE, so a pixel write
    // after the recording shows at the next replay. On a device the recording
    // ALSO keeps the pixels alive: hwui records `bitmap.makeImage()`
    // (hwui/Bitmap.cpp:400-413, android14), an SkImage that
    // `setPixelRef(sk_ref_sp(this), 0, 0)` with kNever_SkCopyPixelsMode -- the
    // pixels themselves, not a copy -- while Bitmap.recycle only drops the Java
    // wrapper's own ref (jni/Bitmap.cpp:55-63, BitmapWrapper::freePixels
    // `mBitmap.reset()`). So a Compose layer or a Picture replayed after the app
    // (or a pool like Glide's LruBitmapPool eviction) recycled the source still
    // draws it. Our recycle() nulls the two backing stores, so without this a
    // replay drew nothing.
    //
    // The model: ONE lightweight view per recorded Bitmap, created at the first
    // recorded draw and empty until then. recycle() fills it with this bitmap's
    // state AS IT IS AT RECYCLE TIME (the storage objects are SHARED, not
    // copied, exactly as the SkImage shares the pixel memory; so a write, a
    // reconfigure or an inBitmap decode after the recording is what the replay
    // shows) and then lets go of it. From then on only the recordings that
    // captured the view keep the storage alive, which is the device's lifetime
    // too: the pixels go when the last display list that drew them goes.
    // Nothing else ever sees the view; a replay reaches it only once the live
    // bitmap reports isRecycled() (MacApkDisplayList.live).
    //
    // A bitmap no recording ever drew has no view, so recycle() is unchanged
    // for it, and so is every path with both op-list gates at 0.
    private Bitmap macapkPixelRef;

    /** The view a recorded draw of this bitmap keeps; null once recycled (a
     *  recording of a recycled bitmap draws as the direct call on it would).
     *  Synchronized with recycle(), so a view handed out is always the one
     *  recycle() fills. */
    synchronized Bitmap macapkPixelRef() {
        if (recycled) return null;
        Bitmap v = macapkPixelRef;
        if (v == null) macapkPixelRef = v = new Bitmap();
        return v;
    }

    /** Every instance field of Bitmap, but the two that make the original what it
     *  is (`recycled`, and the view link). Reflective ON PURPOSE: the view must
     *  draw exactly as this bitmap would have, so it has to carry every field a
     *  draw reads, and a field added to this class later must not be silently
     *  left behind. Runs only in recycle() of a bitmap a recording drew. */
    private static java.lang.reflect.Field[] sPixelRefFields;
    private static java.lang.reflect.Field[] macapkPixelRefFields() {
        java.lang.reflect.Field[] f = sPixelRefFields;
        if (f == null) {
            java.util.ArrayList<java.lang.reflect.Field> l = new java.util.ArrayList<>();
            for (java.lang.reflect.Field x : Bitmap.class.getDeclaredFields()) {
                if (java.lang.reflect.Modifier.isStatic(x.getModifiers())) continue;
                if (x.getName().equals("recycled") || x.getName().equals("macapkPixelRef")) continue;
                x.setAccessible(true);
                l.add(x);
            }
            sPixelRefFields = f = l.toArray(new java.lang.reflect.Field[0]);
        }
        return f;
    }
    /** Called under the lock, before the stores are dropped. */
    private void macapkHandPixelsToRecordings() {
        final Bitmap v = macapkPixelRef;
        if (v == null) return;
        macapkPixelRef = null;
        try {
            for (java.lang.reflect.Field x : macapkPixelRefFields()) x.set(v, x.get(this));
        } catch (ReflectiveOperationException | RuntimeException e) {
            // Cannot happen for our own fields; if it ever does, the replay
            // draws nothing for this bitmap, which is the pre-change behaviour.
            try {
                com.macapk.loader.NativeLog.i("Mac.apk.RenderNode",
                    "bitmap pixel ref for a recording could not be filled: " + e);
            } catch (Throwable ignored) { }
        }
    }
    public boolean isRecycled() { return recycled; }
    /** AOSP Bitmap.compress: encode the pixels to `stream`. Backed by ImageIO
     *  on the AWT image (Canvas-backed bitmaps) or the argbBuffer (decoded /
     *  AndEngine bitmaps). PNG + JPEG are supported; WEBP has no ImageIO writer
     *  so it falls back to PNG bytes (lossless, still decodable). Was a no-op
     *  (returned false), which left every Bitmap.compress() caller (icon export,
     *  screenshot/share, disk caches) unable to persist a bitmap. */
    public boolean compress(CompressFormat fmt, int quality, java.io.OutputStream stream) {
        // AOSP's own Java-side argument checks (Bitmap.java:1429-1440), which
        // run BEFORE the encoder and are therefore pinned on every device: a
        // recycled bitmap is IllegalStateException, a null stream is
        // NullPointerException, and a quality outside 0..100 is
        // IllegalArgumentException -- even for PNG, where quality is documented
        // to be ignored. We returned `false` for all three, which an app reads
        // as "the encoder could not handle this image" rather than "you passed
        // me nonsense" (BMP.rt.compress.nullStream / qualityNeg / quality101).
        checkRecycled("Can't compress a recycled bitmap");
        if (stream == null) throw new NullPointerException();
        if (quality < 0 || quality > 100) {
            throw new IllegalArgumentException("quality must be 0..100");
        }
        // Skia has no encoder for kAlpha_8, so compress() answers false for an
        // ALPHA_8 bitmap rather than writing a grey PNG (BMP.rt.alpha8:
        // emulator "false NULL"). Ours succeeded, which silently turned an
        // alpha mask into an opaque image in every disk cache.
        if (config == Config.ALPHA_8) return false;
        try {
            BufferedImage img = awtImage;
            if (img == null && width > 0 && height > 0) {
                // A createBitmap()'d bitmap that was never written has NEITHER
                // backing store yet; AOSP's allocation is calloc'd and encodes
                // fine, so materialize the zero-filled image rather than
                // reporting failure (BMP.rt.compress.returns: emulator 1, we
                // answered 0 for a freshly created 4x4).
                img = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
                if (argbBuffer != null) {
                    img.setRGB(0, 0, width, height, argbBuffer, 0, width);
                }
            }
            if (img == null) return false;
            String fmtName = (fmt == CompressFormat.JPEG) ? "jpg" : "png";
            // WHETHER THE ENCODED FILE CARRIES AN ALPHA CHANNEL is an answer:
            // it decides hasAlpha() on whatever the app decodes back.
            //  - PNG follows the BITMAP's alpha type, so an opaque-flagged
            //    bitmap (an RGB_565 one, say) round-trips to alpha=0 while an
            //    ARGB_8888 bitmap whose pixels merely happen to be opaque
            //    round-trips to alpha=1 (BMP.rt.565 vs BMP.rt.8888.opaque).
            //  - libwebp's encoder additionally DROPS the channel when every
            //    pixel is opaque, so the same source that keeps alpha through
            //    PNG loses it through WEBP (BMP.rt.quad.lossless alpha=1 vs
            //    BMP.rt.webpLossless alpha=0). We emit PNG bytes for the WEBP
            //    formats (no ImageIO WebP writer exists, and lossless PNG is
            //    still a decodable container), so that decision has to be
            //    reproduced here rather than inherited from a codec.
            final boolean isWebp = fmt == CompressFormat.WEBP
                    || fmt == CompressFormat.WEBP_LOSSY
                    || fmt == CompressFormat.WEBP_LOSSLESS;
            final boolean wantAlpha = hasAlpha() && !(isWebp && macapkAllPixelsOpaque());
            if (fmt != CompressFormat.JPEG && !wantAlpha && img.getColorModel().hasAlpha()) {
                BufferedImage rgb = new BufferedImage(img.getWidth(), img.getHeight(),
                        BufferedImage.TYPE_INT_RGB);
                java.awt.Graphics2D g = rgb.createGraphics();
                g.setComposite(java.awt.AlphaComposite.Src);
                g.drawImage(img, 0, 0, null);
                g.dispose();
                img = rgb;
            }
            if (fmt == CompressFormat.JPEG && img.getColorModel().hasAlpha()) {
                // JPEG has no alpha channel -- flatten onto white.
                BufferedImage rgb = new BufferedImage(img.getWidth(), img.getHeight(), BufferedImage.TYPE_INT_RGB);
                java.awt.Graphics2D g = rgb.createGraphics();
                g.setColor(java.awt.Color.WHITE);
                g.fillRect(0, 0, rgb.getWidth(), rgb.getHeight());
                g.drawImage(img, 0, 0, null);
                g.dispose();
                img = rgb;
            }
            // ⚠ v1.0.939: the `quality` argument used to be VALIDATED and then
            // DISCARDED -- ImageIO.write(img, "jpg", ...) always encodes at the
            // writer's default 0.75, so EVERY JPEG Mac.apk has ever written was
            // ~q75 no matter what the app asked for. An app compressing a photo
            // at 95 got a visibly worse file, and one compressing a thumbnail
            // at 30 got a needlessly big one; nothing logged either.
            //
            // Measured: at an explicit quality=1.0 the corner pixels of the
            // probe's fixture come back ff102030 / ffff7f00 / ff00c0ff /
            // ff40fe20 -- the reference device's answer EXACTLY -- while the
            // ImageIO default reproduces our old divergent row. So this is the
            // whole of BMP.lossy.jpeg.corners.
            //
            // PNG is lossless and its writer has no compression parameter, so
            // AOSP's own javadoc says quality "is ignored" there; only the JPEG
            // path takes this branch.
            if (fmt == CompressFormat.JPEG) {
                java.util.Iterator<javax.imageio.ImageWriter> it =
                        javax.imageio.ImageIO.getImageWritersByFormatName("jpg");
                if (it.hasNext()) {
                    javax.imageio.ImageWriter w = it.next();
                    javax.imageio.ImageWriteParam param = w.getDefaultWriteParam();
                    if (param.canWriteCompressed()) {
                        param.setCompressionMode(javax.imageio.ImageWriteParam.MODE_EXPLICIT);
                        // Android's 0..100 maps linearly onto ImageIO's 0..1.
                        param.setCompressionQuality(quality / 100f);
                    }
                    javax.imageio.stream.ImageOutputStream ios =
                            javax.imageio.ImageIO.createImageOutputStream(stream);
                    try {
                        w.setOutput(ios);
                        w.write(null, new javax.imageio.IIOImage(img, null, null), param);
                    } finally {
                        try { w.dispose(); } catch (Throwable ignored) {}
                        // FLUSH, do not close: AOSP's compress() does not close
                        // the caller's stream, and closing the ImageOutputStream
                        // would close the OutputStream under it.
                        try { if (ios != null) ios.flush(); } catch (Throwable ignored) {}
                    }
                    return true;
                }
            }
            return javax.imageio.ImageIO.write(img, fmtName, stream);
        } catch (Throwable t) {
            return false;
        }
    }
    /** True when no pixel carries partial or zero alpha -- what libwebp's
     *  encoder tests before deciding to write a channel at all. */
    private boolean macapkAllPixelsOpaque() {
        int[] px = macapkPixelSnapshot();
        if (px == null) return false;   // never-written storage reads as alpha 0
        for (int i = 0; i < px.length; i++) {
            if ((px[i] & 0xff000000) != 0xff000000) return false;
        }
        return true;
    }
    /** Bytes per pixel for this bitmap's Config, per the AOSP Bitmap.Config
     *  javadoc (ALPHA_8 "requires 1 byte", RGB_565 "stored on 2 bytes",
     *  ARGB_4444 "stored on 2 bytes", ARGB_8888 "stored on 4 bytes", RGBA_F16
     *  "stored on 8 bytes") and Skia's colortype table, where HARDWARE maps to
     *  kRGBA_8888. getRowBytes/getByteCount are what every caller-side
     *  buffer-capacity check is built on, so a flat *4 mis-sized every
     *  non-8888 bitmap. */
    private int macapkBytesPerPixel() {
        return macapkBytesPerPixelFor(config);
    }
    /** Static, Config-parameterized sibling of the above -- needed by
     *  reconfigure() to compute the NEW config's byte count BEFORE
     *  committing it to `this.config`, so the allocation check can run
     *  first and leave the bitmap unmodified on failure (matching AOSP's
     *  documented "the bitmap will not be modified" contract). */
    private static int macapkBytesPerPixelFor(Config c) {
        if (c == null) return 4;      // untyped bitmaps are ARGB_8888
        switch (c) {
            case ALPHA_8:   return 1;
            case RGB_565:   return 2;
            case ARGB_4444: return 2;
            case RGBA_F16:  return 8;
            default:        return 4;      // ARGB_8888, HARDWARE (kRGBA_8888)
        }
    }
    public int  getRowBytes() { return width * macapkBytesPerPixel(); }
    /** AOSP: getRowBytes() * getHeight() -- except on a recycled bitmap, where
     *  the Java side early-returns 0 (Bitmap.java:1643-1651). getRowBytes()
     *  itself keeps answering (it reads the cached SkImageInfo), so this is the
     *  "exception inside the exception" the probe names: BMP.recycle.getRowBytes
     *  is 32 while BMP.recycle.getByteCount is 0. */
    public int  getByteCount() {
        if (recycled) return 0;
        return getRowBytes() * height;
    }
    /** v1.0.110/v1.0.841-ish: API 19+. AOSP javadoc (fetched 2026-07-31,
     *  aosp-mirror platform_frameworks_base, graphics/java/android/graphics/
     *  Bitmap.java): "Returns the size of the allocated memory used to store
     *  this bitmap's pixels. This can be larger than the result of
     *  getByteCount() if a bitmap is reused to decode other bitmaps of
     *  smaller size, or by manual reconfiguration ... This value will not
     *  change over the lifetime of a Bitmap." The v1.0.110 comment this
     *  replaces said "we never reuse, so the two are equal" -- true THEN,
     *  false now that BitmapFactory's inBitmap reuse (macapkReuseInBitmap)
     *  actually exists: this method must report the ORIGINAL allocation,
     *  not whatever the CURRENT (possibly reconfigure()'d-down) dimensions
     *  happen to be, or every reuse of a pooled bitmap for a LARGER-than-
     *  its-current-size image looks "too small" even when the true
     *  allocation would fit it.
     *  MEASURED (Fossify Gallery bring-up, 2026-07-31): Glide's BitmapPool
     *  reconfigures its pooled bitmaps between uses; with the old always-
     *  equal-to-getByteCount() answer, EVERY reuse where the new decode's
     *  byte count exceeds the pooled bitmap's CURRENT (not original)
     *  dimensions threw "Problem decoding into existing bitmap: bitmap is
     *  too small" from macapkReuseInBitmap's own (correct) check -- the
     *  check was right, the value it compared against was wrong. This was
     *  the actual thumbnail-load blocker left after the file: URI
     *  ContentResolver fix (see docs/fossify-newpipe_2026-07-31.md). */
    public int  getAllocationByteCount() {
        if (recycled) return 0;   // AOSP Bitmap.java:1667-1675, same early return
        return allocationByteCount >= 0 ? allocationByteCount : getByteCount();
    }
    /** AOSP setPremultiplied stores the REQUEST and then applies it to the live
     *  alpha type, but only when the bitmap is not opaque (Bitmap.cpp's
     *  Bitmap_setPremultiplied is guarded by `if (!info().isOpaque())`). Was a
     *  no-op paired with a constant-`true` isPremultiplied(), so
     *  Options.inPremultiplied=false, an RGB_565 bitmap and an
     *  already-opaque-flagged bitmap ALL claimed to be premultiplied
     *  (BMP.state.premul.565 / premul.afterNoAlpha / BMP.cfg.premul.*). */
    public void setPremultiplied(boolean v) {
        checkRecycled("setPremultiplied called on a recycled bitmap");
        requestPremultiplied = v;
        if (alphaType != ALPHA_OPAQUE) {
            final int was = alphaType;
            macapkSetAlphaType(v ? ALPHA_PREMUL : ALPHA_UNPREMUL);
            // Labelled deviation from hwui (no notify there); see getGenerationId.
            if (alphaType != was) macapkNotifyPixelsChanged();
        }
    }
    public boolean isPremultiplied() { return alphaType == ALPHA_PREMUL; }
    /** Internal: the app's last premultiplication REQUEST, which copy() and
     *  createBitmap(subset) carry to the destination independently of the live
     *  alpha type. */
    boolean macapkRequestPremultiplied() { return requestPremultiplied; }
    /** Internal: BitmapFactory applies Options.inPremultiplied to a freshly
     *  decoded bitmap through the same two-step AOSP does -- record the
     *  request, then set the alpha type from the SOURCE's opacity. */
    void macapkApplyDecodedAlpha(boolean sourceHasAlpha, boolean premultiplied) {
        requestPremultiplied = premultiplied;
        macapkSetAlphaType(!sourceHasAlpha ? ALPHA_OPAQUE
                : (premultiplied ? ALPHA_PREMUL : ALPHA_UNPREMUL));
    }
    /** v1.0.110: hint to native side to upload to GPU; we don't have a
     *  GPU-side cache, so the only observable part is AOSP's recycled guard. */
    public void prepareToDraw() {
        checkRecycled("Can't prepareToDraw on a recycled bitmap!");
    }
    /** v1.0.110: API 26+. Glide's Downsampler stores the colorspace
     *  it requested via opts.inPreferredColorSpace and reads it back
     *  via bm.getColorSpace() to populate the BitmapResource's
     *  size-key.
     *  Two configs are NOT sRGB and we answered sRGB for both: ALPHA_8 has no
     *  colour channels and therefore NO colour space at all (AOSP's own
     *  setColorSpace javadoc: "A bitmap with Config.ALPHA_8 never has a color
     *  space"), and RGBA_F16 decodes into EXTENDED_SRGB / scRGB-nl because a
     *  half-float bitmap is allowed to carry out-of-gamut values
     *  (BMP.state.colorSpace.alpha8 = null, colorSpace.f16 = scRGB-nl id=2). */
    public android.graphics.ColorSpace getColorSpace() {
        checkRecycled("getColorSpace called on a recycled bitmap");
        if (config == Config.ALPHA_8) return null;
        if (config == Config.RGBA_F16) {
            return android.graphics.ColorSpace.get(
                    android.graphics.ColorSpace.Named.EXTENDED_SRGB);
        }
        return android.graphics.ColorSpace.get(android.graphics.ColorSpace.Named.SRGB);
    }
    public void setColorSpace(android.graphics.ColorSpace cs) {
        checkRecycled("setColorSpace called on a recycled bitmap");
    }
    /** AOSP Bitmap.reconfigure(int,int,Config) (graphics/java/android/
     *  graphics/Bitmap.java, aosp-mirror platform_frameworks_base@main,
     *  fetched 2026-07-31): validates recycled/dimensions/mutability, then
     *  -- the part that matters here -- "Modifies the bitmap to have the
     *  specified width, height, and Config, without affecting the
     *  underlying allocation backing the bitmap ... If the Bitmap's
     *  allocation isn't large enough to support the new configuration, an
     *  IllegalArgumentException will be thrown and the bitmap will not be
     *  modified." Previously this method did none of that validation: it
     *  unconditionally overwrote width/height/config, so a caller (Glide's
     *  BitmapPool is the measured one) reconfiguring a pooled bitmap to a
     *  size that does NOT fit the true allocation got silently corrupted
     *  state instead of the documented throw. Byte count for the NEW
     *  config is computed and checked BEFORE any field is written, so a
     *  rejected reconfigure leaves the bitmap exactly as it was, matching
     *  "will not be modified". Callers that reach this through
     *  macapkReuseInBitmap already perform their own equivalent check first
     *  (so that call site's own message fires first for THAT path); this
     *  makes reconfigure() itself correct for any other caller too. */
    public void reconfigure(int w, int h, Config c) {
        checkRecycled("Can't call reconfigure() on a recycled bitmap");
        if (w <= 0 || h <= 0) {
            throw new IllegalArgumentException("width and height must be > 0");
        }
        if (!isMutable()) {
            throw new IllegalStateException("only mutable bitmaps may be reconfigured");
        }
        Config newConfig = c != null ? c : this.config;
        int newRowBytes = w * macapkBytesPerPixelFor(newConfig);
        long newByteCount = (long) newRowBytes * h;
        if (newByteCount > getAllocationByteCount()) {
            throw new IllegalArgumentException(
                "Bitmap not large enough to support new configuration");
        }
        this.width = w; this.height = h; this.config = newConfig;
        // AOSP: `nativeReconfigure(ptr, w, h, config, mRequestPremultiplied)`.
        // The new colour type re-validates the alpha type, which is why the
        // javadoc says reconfigure "may change the result of hasAlpha(). When
        // converting to 565, the new bitmap will always be considered opaque"
        // (BMP.geom.reconfig.alphaTo565: alpha=1 -> alpha=0).
        macapkSetAlphaType(alphaType);
        // hwui Bitmap::reconfigure -> SkPixelRef::android_only_reset notifies,
        // unconditionally ("conservative"). See getGenerationId.
        macapkNotifyPixelsChanged();
    }
    /** Package-internal escape hatch for BitmapFactory.macapkFinishDecode's
     *  "create as ARGB_8888 (the only Config that never throws, even for a
     *  HARDWARE request), then RETAG to whatever Config was actually
     *  requested" two-step dance (see that method's own comment). A retag
     *  immediately after creation, before this Bitmap has been handed to
     *  any caller, is always memory-safe in our implementation regardless
     *  of the target Config's nominal bytes-per-pixel: the real backing
     *  store is always a plain int[] (see this file's header comment), so
     *  "RGBA_F16 needs 8 bytes/pixel" is a REPORTING fact for API fidelity
     *  (getByteCount/getAllocationByteCount), not a true storage constraint
     *  here. Routing this specific retag through the public reconfigure()
     *  -- which now correctly rejects a reconfigure that would exceed the
     *  tracked allocation, see that method's own doc comment -- would
     *  spuriously throw for an F16 request despite nothing being unsafe.
     *  Widens allocationByteCount to the new config's reported size (never
     *  shrinks it) so a LATER real reconfigure()/inBitmap reuse against
     *  this same object still validates against an honest, large-enough
     *  baseline. */
    void macapkRetagConfigAfterCreate(Config c) {
        this.config = c;
        int newByteCount = getByteCount();
        if (newByteCount > allocationByteCount) allocationByteCount = newByteCount;
    }
    // ---- P1: a real generation ID (docs/display-list-rendernode-design_2026-09-26.md) ----
    //
    // AOSP android14 Bitmap.java:387-394: "Returns the generation ID of this
    // bitmap. The generation ID changes whenever the bitmap is modified. This can
    // be used as an efficient way to check if a bitmap has changed." It is
    // nativeGenerationId -> SkPixelRef::getGenerationID. This returned a constant
    // 0, so every cache keyed on it (an app's, a library's, and the display-list
    // work that needs to know whether a drawn bitmap changed) could never see a
    // change at all.
    //
    // The model is Skia's, exactly (external/skia android14-release,
    // src/core/SkPixelRef.cpp:56-59 and :100-108): a modification does not
    // assign an ID, it only forgets the current one (needsNewGenID stores 0), and
    // the next getGenerationID hands out a fresh process-unique value. So the hot
    // bump, which runs once per Canvas draw op on a canvas-backed bitmap, is one
    // volatile store, and two different bitmaps never share an ID.
    //
    // Who bumps, and the AOSP source for each:
    //  - every draw op of a Canvas constructed on this bitmap: SkBitmapDevice's
    //    draw helper "need[s] fDst to be set, and if we're actually drawing, to
    //    dirty the genID" (SkBitmapDevice.cpp:74, :193) through onAccessPixels ->
    //    fBitmap.notifyPixelsChanged() (:299-301). Wired at Canvas's ink sites
    //    (Canvas.macapkGenBump), so a draw that is clipped away still bumps, as it
    //    does on a device;
    //  - setPixel / setPixels / eraseColor / copyPixelsFromBuffer (SkBitmap
    //    writePixels and erase notify; hwui jni/Bitmap.cpp:1041-1052 notifies
    //    copyPixelsFromBuffer explicitly);
    //  - reconfigure (and setWidth/setHeight/setConfig through it): hwui
    //    Bitmap::reconfigure -> SkPixelRef::android_only_reset, which notifies
    //    "conservative, since its possible the 'new' settings are the same as
    //    the old" (SkPixelRef.cpp:46-54);
    //  - the NDK unlock (jnigraphics bitmap.cpp:78 notifyPixelsChanged): our
    //    unlock commits guest writes through macapkCommitArgbToAwt, which bumps;
    //  - a decode into an inBitmap (macapkSetArgbBuffer replaces the pixels).
    //  - setHasAlpha / setPremultiplied, when the alpha type really changes. This
    //    one is a DELIBERATE, labelled deviation: hwui's Bitmap::setAlphaType
    //    (hwui/Bitmap.cpp:366-372) only rewrites mInfo and does not notify. We bump
    //    because the alpha type changes what a drawBitmap of this bitmap puts on
    //    the screen, which is the question a display-list diff asks of this ID.
    //
    // BISECT GATE, default ON: MACAPK_BITMAP_GENID=0 restores the constant 0 and
    // makes every bump a no-op (the JIT folds both away).
    static final boolean GENID = !"0".equals(System.getenv("MACAPK_BITMAP_GENID"));
    private static final java.util.concurrent.atomic.AtomicInteger sNextGenId =
            new java.util.concurrent.atomic.AtomicInteger();
    private static final java.util.concurrent.atomic.AtomicIntegerFieldUpdater<Bitmap> GEN_ID =
            java.util.concurrent.atomic.AtomicIntegerFieldUpdater.newUpdater(Bitmap.class, "macapkGenId");
    /** 0 means "modified since the last getGenerationId": SkPixelRef's
     *  fTaggedGenID after needsNewGenID. */
    private volatile int macapkGenId;

    public int getGenerationId() {
        if (!GENID) return 0;
        if (recycled) {
            // AOSP logs and answers anyway ("This is undefined behavior!").
            try {
                android.util.Log.w("Bitmap",
                    "Called getGenerationId() on a recycle()'d bitmap! This is undefined behavior!");
            } catch (Throwable ignored) { }
        }
        int id = macapkGenId;
        while (id == 0) {
            // SkNextID-style: process-wide and never 0 (0 is the "needs one" tag).
            int next = sNextGenId.incrementAndGet();
            if (next == 0) continue;
            if (GEN_ID.compareAndSet(this, 0, next)) return next;
            id = macapkGenId;   // another reader assigned one first; use it
        }
        return id;
    }

    /** SkPixelRef::notifyPixelsChanged: forget the current ID. Package-private
     *  for Canvas's ink sites; the NDK path reaches it through
     *  macapkCommitArgbToAwt. */
    void macapkNotifyPixelsChanged() {
        if (GENID) macapkGenId = 0;
    }
    /** AOSP: "Given another bitmap, return true if it has the same dimensions,
     *  config, and pixel data as this bitmap. If any of those differ, return
     *  false. If other is null, return false." (android-11.0.0_r48
     *  Bitmap.java:3487-3499; the body ends in nativeSameAs, which memcmps the
     *  pixels.)
     *
     *  This used to be `return other == this;` -- IDENTITY, which answers the
     *  self case correctly and every other case wrong, in the direction that
     *  makes a comparison silently fail. MEASURED (RAS.bmpapi.sameAs.equalContent
     *  and RAS.bmpapi.copy.sameAs): two identically-erased bitmaps, and a bitmap
     *  against its own copy(), both answer 1 on the emulator and answered 0 here.
     *  Test/diff code and image caches key off this.
     *
     *  A null pixel array is treated as all-zero rather than as "unknown", so a
     *  never-written bitmap compares equal to an explicitly zeroed one, which is
     *  what a real (calloc'd) native allocation gives. */
    public boolean sameAs(Bitmap other) {
        checkRecycled("Can't call sameAs on a recycled bitmap!");
        if (other == this) return true;
        if (other == null) return false;
        // AOSP checks the ARGUMENT's recycled state only after the identity
        // test, and reports it as IllegalArgumentException rather than the
        // IllegalStateException the receiver's own guard raises -- two
        // exception classes for the same condition, which is exactly the kind
        // of detail a reimplementation normalises away
        // (BMP.state.sameAs.recycledArg).
        if (other.isRecycled()) {
            throw new IllegalArgumentException("Can't compare to a recycled bitmap!");
        }
        if (width != other.width || height != other.height) return false;
        if (config != other.config) return false;
        // nativeSameAs compares the whole SkImageInfo, which includes the ALPHA
        // TYPE -- so two identically-erased 8888 bitmaps differ once one of
        // them is setHasAlpha(false)'d, even though every stored byte matches
        // (BMP.state.sameAs.differentAlphaFlag: emulator 0, we answered 1).
        if (alphaType != other.alphaType) return false;
        final int n = width * height;
        if (n <= 0) return true;
        int[] a = macapkPixelSnapshot();
        int[] b = other.macapkPixelSnapshot();
        for (int i = 0; i < n; i++) {
            int pa = (a != null && i < a.length) ? a[i] : 0;
            int pb = (b != null && i < b.length) ? b[i] : 0;
            // Compare what the bitmaps CAN HOLD, not what we happen to have
            // stored: nativeSameAs memcmps the config's own bytes, so two 565
            // bitmaps whose 8888 sources differed only below the 5/6-bit
            // quantization ARE the same bitmap (BMP.buf.roundtrip.565). The
            // configs are already known equal, so one quantizer serves both.
            if (macapkQuantizeForConfig(pa) != macapkQuantizeForConfig(pb)) return false;
        }
        return true;
    }
    /** A read-only view of this bitmap's pixels for comparison.
     *  Deliberately NOT macapkArgbBuffer(): that getter re-extracts from the AWT
     *  image INTO argbBuffer as a side effect, and a query has no business
     *  mutating the object it is querying. */
    private int[] macapkPixelSnapshot() {
        if (awtImage != null && width > 0 && height > 0) {
            try { return awtImage.getRGB(0, 0, width, height, null, 0, width); }
            catch (Throwable ignored) { /* fall through to the int[] */ }
        }
        return argbBuffer;
    }
    public Bitmap copy(Config config, boolean isMutable) {
        checkRecycled("Can't copy a recycled bitmap");
        // AOSP: a HARDWARE bitmap is always immutable, so this pair is rejected
        // in Java before any allocation (Bitmap.java:684-687) and the throw is
        // pinned regardless of GPU (BMP.copy.hardwareMutable, BMP.hw.copyMutable).
        if (config == Config.HARDWARE && isMutable) {
            throw new IllegalArgumentException("Hardware bitmaps are always immutable");
        }
        // AOSP returns a NEW bitmap with copied pixels (was returning `this`, so a
        // caller that copies to get an independent mutable buffer then mutates it
        // was corrupting the source). Copy from whichever backing store we use.
        Bitmap b = new Bitmap();
        b.width = this.width; b.height = this.height;
        b.config = config != null ? config : this.config;
        b.mutable = isMutable;
        // nativeCopy carries the source's alpha type into the destination's
        // COLOUR TYPE, so a copy to 565 comes out opaque and a copy from an
        // opaque 565 stays opaque (BMP.copy.8888to565 / 565to8888, both
        // measured alpha=0 premul=0). Then AOSP re-applies the source's
        // premultiplication REQUEST explicitly (`b.setPremultiplied(
        // mRequestPremultiplied)`), which is how copy() carries an
        // unpremultiplied bitmap across (BMP.copy.carriesState).
        b.alphaType = macapkValidateAlphaType(b.config, this.alphaType);
        b.requestPremultiplied = this.requestPremultiplied;
        if (b.alphaType != ALPHA_OPAQUE) {
            b.macapkSetAlphaType(this.requestPremultiplied ? ALPHA_PREMUL : ALPHA_UNPREMUL);
        }
        b.density = this.density;   // AOSP copy(): b.mDensity = mDensity
        // A copy is a FRESH native allocation sized to the copy's own
        // dimensions/config, not a share of the source's (possibly larger,
        // pool-reused) allocation -- getAllocationByteCount() must reflect
        // that independently, the same way createBitmap does.
        b.allocationByteCount = b.getByteCount();
        try {
            if (this.awtImage != null && width > 0 && height > 0) {
                b.awtImage = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
                int[] tmp = this.awtImage.getRGB(0, 0, width, height, null, 0, width);
                b.awtImage.setRGB(0, 0, width, height, tmp, 0, width);
            } else if (this.argbBuffer != null) {
                b.argbBuffer = java.util.Arrays.copyOf(this.argbBuffer, this.argbBuffer.length);
            }
        } catch (Throwable ignored) {}
        return b;
    }
    /** AOSP: methods that need CPU pixel access refuse a Config.HARDWARE
     *  bitmap, whose pixels live in graphics memory. Apps rely on the throw
     *  ("catch (IllegalStateException e) { bm = bm.copy(ARGB_8888, false); }")
     *  to learn they must convert first, so silently succeeding skips their
     *  conversion fallback. */
    private void checkHardware(String errorMessage) {
        if (getConfig() == Config.HARDWARE) {
            throw new IllegalStateException(errorMessage);
        }
    }

    /** Serialize the pixels into the byte layout Skia holds them in for this
     *  Config, in host byte order -- i.e. the exact bytes AOSP's
     *  nativeCopyPixelsToBuffer memcpy()s out of the bitmap. ARGB_8888 is
     *  kRGBA_8888 (byte 0 = R ... byte 3 = A, the same channel order the GL
     *  upload path uses); RGB_565 packs R<<11 | G<<5 | B and ARGB_4444 packs
     *  R<<12 | G<<8 | B<<4 | A into a 16-bit word (skia/src/core/SkColorData.h
     *  SK_R16_SHIFT=11 / SK_G16_SHIFT=5 and SK_R4444_SHIFT=12 /
     *  SK_G4444_SHIFT=8 / SK_B4444_SHIFT=4 / SK_A4444_SHIFT=0); ALPHA_8 keeps
     *  the alpha channel alone; RGBA_F16 stores four halfs. */
    private byte[] macapkPackPixels() {
        int n = Math.max(0, width * height);
        byte[] out = new byte[n * macapkBytesPerPixel()];
        int[] px = macapkArgbBuffer();
        java.nio.ByteBuffer bb = java.nio.ByteBuffer.wrap(out)
                .order(java.nio.ByteOrder.nativeOrder());
        Config c = (config == null) ? Config.ARGB_8888 : config;
        for (int i = 0; i < n; i++) {
            int p = (px != null && i < px.length) ? px[i] : 0;
            int a = (p >>> 24) & 0xff, r = (p >> 16) & 0xff;
            int g = (p >> 8) & 0xff,   b = p & 0xff;
            switch (c) {
                case ALPHA_8:
                    bb.put((byte) a);
                    break;
                case RGB_565:
                    bb.putShort((short) (((r & 0xf8) << 8) | ((g & 0xfc) << 3) | (b >> 3)));
                    break;
                case ARGB_4444:
                    bb.putShort((short) (((r & 0xf0) << 8) | ((g & 0xf0) << 4)
                                         | (b & 0xf0) | (a >> 4)));
                    break;
                case RGBA_F16:
                    bb.putShort(android.util.Half.toHalf(r / 255f));
                    bb.putShort(android.util.Half.toHalf(g / 255f));
                    bb.putShort(android.util.Half.toHalf(b / 255f));
                    bb.putShort(android.util.Half.toHalf(a / 255f));
                    break;
                default:
                    bb.put((byte) r).put((byte) g).put((byte) b).put((byte) a);
                    break;
            }
        }
        return out;
    }

    /** Inverse of macapkPackPixels: decode this Config's native pixel bytes
     *  back into the ARGB int[] the rest of the runtime draws from. The 5/6/4
     *  bit channels are widened by replicating their high bits, matching
     *  Skia's SkR16ToR32 ((r << 3) | (r >> 2)) and the 4444 nibble doubling. */
    private void macapkUnpackPixels(byte[] raw) {
        int n = Math.max(0, width * height);
        if (n == 0) return;
        ensureArgbBuffer();
        java.nio.ByteBuffer bb = java.nio.ByteBuffer.wrap(raw)
                .order(java.nio.ByteOrder.nativeOrder());
        Config c = (config == null) ? Config.ARGB_8888 : config;
        int bpp = macapkBytesPerPixel();
        for (int i = 0; i < n && i < argbBuffer.length && bb.remaining() >= bpp; i++) {
            int argb;
            switch (c) {
                case ALPHA_8: {
                    // An ALPHA_8 pixel read back is SkColorSetA(0, alpha):
                    // alpha in the top byte, RGB zero.
                    argb = (bb.get() & 0xff) << 24;
                    break;
                }
                case RGB_565: {
                    int v = bb.getShort() & 0xffff;
                    int r = (v >> 11) & 0x1f, g = (v >> 5) & 0x3f, b = v & 0x1f;
                    argb = 0xff000000 | (((r << 3) | (r >> 2)) << 16)
                                      | (((g << 2) | (g >> 4)) << 8)
                                      |  ((b << 3) | (b >> 2));
                    break;
                }
                case ARGB_4444: {
                    int v = bb.getShort() & 0xffff;
                    int r = (v >> 12) & 0xf, g = (v >> 8) & 0xf;
                    int b = (v >> 4) & 0xf,  a = v & 0xf;
                    argb = (((a << 4) | a) << 24) | (((r << 4) | r) << 16)
                         | (((g << 4) | g) << 8)  |  ((b << 4) | b);
                    break;
                }
                case RGBA_F16: {
                    int r = macapkHalfToByte(bb.getShort());
                    int g = macapkHalfToByte(bb.getShort());
                    int b = macapkHalfToByte(bb.getShort());
                    int a = macapkHalfToByte(bb.getShort());
                    argb = (a << 24) | (r << 16) | (g << 8) | b;
                    break;
                }
                default: {
                    int r = bb.get() & 0xff, g = bb.get() & 0xff;
                    int b = bb.get() & 0xff, a = bb.get() & 0xff;
                    argb = (a << 24) | (r << 16) | (g << 8) | b;
                    break;
                }
            }
            argbBuffer[i] = argb;
        }
        // Keep an AWT-backed bitmap (a Canvas drawing target) in step, or the
        // next paint would extract stale pixels back over what we just wrote.
        if (awtImage != null && width > 0 && height > 0) {
            awtImage.setRGB(0, 0, width, height, argbBuffer, 0, width);
        }
    }

    private static int macapkHalfToByte(short half) {
        int v = Math.round(android.util.Half.toFloat(half) * 255f);
        return v < 0 ? 0 : (v > 255 ? 255 : v);
    }

    /** AOSP: copy the bitmap's native pixel bytes into the buffer starting at
     *  its current position, then advance the position by the number of
     *  elements written. The buffer must be a Byte/Short/IntBuffer and must
     *  hold at least getByteCount() bytes. Was an empty method body, so every
     *  Bitmap->Buffer consumer (screenshot/share pipelines, ML input tensors,
     *  custom GL uploaders, JNI image bridges) silently read back whatever it
     *  had pre-allocated -- usually all zeroes -- with no exception. */
    public void copyPixelsToBuffer(java.nio.Buffer dst) {
        checkHardware("unable to copyPixelsToBuffer, "
                + "pixel access is not supported on Config#HARDWARE bitmaps");
        int elements = dst.remaining();
        int shift;
        if (dst instanceof java.nio.ByteBuffer) {
            shift = 0;
        } else if (dst instanceof java.nio.ShortBuffer) {
            shift = 1;
        } else if (dst instanceof java.nio.IntBuffer) {
            shift = 2;
        } else {
            throw new RuntimeException("unsupported Buffer subclass");
        }
        long bufferSize = (long) elements << shift;
        long pixelSize = getByteCount();
        if (bufferSize < pixelSize) {
            throw new RuntimeException("Buffer not large enough for pixels");
        }
        byte[] raw = macapkPackPixels();
        // AOSP memcpy()s raw bytes; reading them back through a Short/IntBuffer
        // decodes them with THAT buffer's byte order, so feed the bytes through
        // a view in the destination's own order rather than assuming a layout.
        java.nio.ByteBuffer view = java.nio.ByteBuffer.wrap(raw);
        if (dst instanceof java.nio.ByteBuffer) {
            ((java.nio.ByteBuffer) dst).duplicate().put(raw);
        } else if (dst instanceof java.nio.ShortBuffer) {
            java.nio.ShortBuffer sd = ((java.nio.ShortBuffer) dst).duplicate();
            sd.put(view.order(sd.order()).asShortBuffer());
        } else {
            java.nio.IntBuffer id = ((java.nio.IntBuffer) dst).duplicate();
            id.put(view.order(id.order()).asIntBuffer());
        }
        int position = dst.position();
        position += (int) (pixelSize >> shift);
        dst.position(position);
    }

    /** AOSP: overwrite the bitmap's pixels from the buffer starting at its
     *  current position, then advance the position. Was an empty method body,
     *  so "build a Bitmap from a raw pixel buffer" (e.g. after glReadPixels)
     *  silently left the target blank. */
    public void copyPixelsFromBuffer(java.nio.Buffer src) {
        checkRecycled("copyPixelsFromBuffer called on recycled bitmap");
        checkHardware("unable to copyPixelsFromBuffer, Config#HARDWARE bitmaps are immutable");
        int elements = src.remaining();
        int shift;
        if (src instanceof java.nio.ByteBuffer) {
            shift = 0;
        } else if (src instanceof java.nio.ShortBuffer) {
            shift = 1;
        } else if (src instanceof java.nio.IntBuffer) {
            shift = 2;
        } else {
            throw new RuntimeException("unsupported Buffer subclass");
        }
        long bufferBytes = (long) elements << shift;
        long bitmapBytes = getByteCount();
        if (bufferBytes < bitmapBytes) {
            throw new RuntimeException("Buffer not large enough for pixels");
        }
        byte[] raw = new byte[(int) bitmapBytes];
        java.nio.ByteBuffer sink = java.nio.ByteBuffer.wrap(raw);
        if (src instanceof java.nio.ByteBuffer) {
            ((java.nio.ByteBuffer) src).duplicate().get(raw);
        } else if (src instanceof java.nio.ShortBuffer) {
            java.nio.ShortBuffer ss = ((java.nio.ShortBuffer) src).duplicate();
            java.nio.ShortBuffer out = sink.order(ss.order()).asShortBuffer();
            short[] tmp = new short[out.remaining()];
            ss.get(tmp);
            out.put(tmp);
        } else {
            java.nio.IntBuffer si = ((java.nio.IntBuffer) src).duplicate();
            java.nio.IntBuffer out = sink.order(si.order()).asIntBuffer();
            int[] tmp = new int[out.remaining()];
            si.get(tmp);
            out.put(tmp);
        }
        macapkUnpackPixels(raw);
        // hwui jni/Bitmap.cpp:1041-1052: memcpy, then bitmap.notifyPixelsChanged().
        macapkNotifyPixelsChanged();
        int position = src.position();
        position += (int) (bitmapBytes >> shift);
        src.position(position);
    }

    /** AOSP extractAlpha(): "Returns a new bitmap that captures the alpha
     *  values of the original" -- an ALPHA_8 bitmap of the same dimensions,
     *  inheriting this bitmap's density. */
    public Bitmap extractAlpha() { return extractAlpha(null, null); }

    /** AOSP extractAlpha(Paint, int[]): the paint only changes the result when
     *  it carries a MaskFilter. Skia's SkBitmap::extractAlpha (SkBitmap.cpp,
     *  the NO_FILTER_CASE branch) allocates an A8 of the same width/height,
     *  copies the alpha channel, and reports offset (0,0); the paint's own
     *  alpha is not applied. We have no MaskFilter implementation, so that is
     *  the branch we take. */
    public Bitmap extractAlpha(android.graphics.Paint paint, int[] offsetXY) {
        checkRecycled("Can't extractAlpha on a recycled bitmap");
        if (width <= 0 || height <= 0) {
            // Skia returns false for an empty bitmap; the JNI layer turns that
            // into this exact exception.
            throw new RuntimeException("Failed to extractAlpha on Bitmap");
        }
        Bitmap bm = createBitmap(width, height, Config.ALPHA_8);
        int n = width * height;
        int[] src = macapkArgbBuffer();
        int[] out = new int[n];
        if (src != null) {
            for (int i = 0; i < n && i < src.length; i++) {
                // An ALPHA_8 pixel reads back as SkColorSetA(0, alpha).
                out[i] = src[i] & 0xff000000;
            }
        }
        bm.macapkSetArgbBuffer(out);
        bm.density = this.density;   // AOSP: bm.mDensity = mDensity
        if (offsetXY != null && offsetXY.length >= 2) { offsetXY[0] = 0; offsetXY[1] = 0; }
        return bm;
    }

    /** AOSP asShared(): "Return an immutable bitmap backed by shared memory
     *  which can be efficiently passed between processes via Parcelable. If
     *  this bitmap already meets these criteria it will return itself."
     *  Mac.apk is single-process and our Parcel passes live object references,
     *  so the shared-memory criterion is trivially met and immutability is the
     *  only one left to satisfy. */
    public Bitmap asShared() {
        if (!isMutable()) return this;
        Bitmap shared = copy(getConfig(), false);
        if (shared == null) {
            throw new RuntimeException("Failed to create shared Bitmap!");
        }
        return shared;
    }

    // ---- android.os.Parcelable ----
    // AOSP declares `public final class Bitmap implements Parcelable`, and
    // APKs are compiled against that: a Bundle.putParcelable(key, bitmap) or
    // an invokeinterface on Parcelable against a Bitmap reference fails with
    // IncompatibleClassChangeError when the runtime class does not implement
    // the interface, which broke every Activity/Service/Fragment bitmap
    // handoff. Our Parcel is in-process -- it keeps live object refs and
    // writes an index marker (android/os/Parcel.java) -- so the round-trip
    // carries the bitmap itself rather than its pixel bytes.
    public static final android.os.Parcelable.Creator<Bitmap> CREATOR =
            new android.os.Parcelable.Creator<Bitmap>() {
        @Override
        public Bitmap createFromParcel(android.os.Parcel p) {
            Object o = p.readValue(null);
            if (!(o instanceof Bitmap)) {
                throw new RuntimeException("Failed to unparcel Bitmap");
            }
            return (Bitmap) o;
        }
        @Override
        public Bitmap[] newArray(int size) { return new Bitmap[size]; }
    };

    /** AOSP: "No special parcel contents." */
    @Override
    public int describeContents() { return 0; }

    @Override
    public void writeToParcel(android.os.Parcel p, int flags) {
        p.writeValue(this);
    }

    /** Internal: BitmapFactory hands the decoded ARGB pixels here. */
    public void macapkSetArgbBuffer(int[] argb) {
        this.argbBuffer = argb;
        argbView = null; argbViewSrc = null;   // the view wraps the REPLACED array (see ARGB_VIEW)
        // New pixels (a decode, including a decode INTO an inBitmap): the
        // bitmap was modified, so its generation ID must move.
        macapkNotifyPixelsChanged();
    }
    /** Internal: GL upload path reads the ARGB buffer. For AWT-backed
     *  bitmaps (Canvas drawing targets), extracts from the BufferedImage
     *  on demand so the buffer reflects the latest paint. For
     *  AndEngine-style bitmaps (no awtImage), returns the argbBuffer
     *  directly -- no extraction, no overwrite. */
    public int[] macapkArgbBuffer() {
        // The second branch: a bitmap nobody has written to yet still HAS
        // pixels, and for a config with no alpha channel they are opaque black
        // rather than absent -- so materialize the zero-state here, where
        // Canvas and the GL upload path read it. Deliberately scoped to the
        // configs whose zero-state is NOT transparent (today: RGB_565 only):
        // for every other config a null buffer and a zero-filled one blit
        // identically, and returning null keeps this hot per-frame getter
        // allocation-free. See macapkZeroPixel.
        if (awtImage != null) {
            macapkExtractArgbFromAwt();
        } else if (argbBuffer == null && width > 0 && height > 0
                   && macapkZeroPixel() != 0) {
            argbBuffer = macapkAllocBuffer();
        }
        return argbBuffer;
    }
    /** Internal: Canvas.setBitmap lazily attaches a BufferedImage to a
     *  Bitmap that was created via createBitmap(w,h,Config) so the new
     *  Canvas has somewhere to paint. Bitmap reuse is permitted -- the
     *  caller passes the SAME bitmap to multiple Canvas instances. */
    public void macapkSetAwtImage(BufferedImage img) { this.awtImage = img; }
    /** Internal: Canvas implementations read the live AWT image so the
     *  same Bitmap can be drawn into across multiple Canvas calls
     *  without the cached argbBuffer going stale. */
    public BufferedImage macapkAwtImage() {
        // Canvas.bitmapAsImage asks for this FIRST and draws from it whenever it exists, so a bitmap
        // that has gained an AWT backing will never use its ARGB view again: release it here rather
        // than pin a second reference to argbBuffer (22 MB for a 1636x3362 bitmap) until recycle().
        if (awtImage != null && argbView != null) { argbView = null; argbViewSrc = null; }
        return awtImage;
    }

    // MACAPK_BITMAP_ARGB_VIEW (default ON, lane performance 2026-09-29): a ZERO-COPY
    // TYPE_INT_ARGB view of argbBuffer for the Canvas draw path. A bitmap with no AWT
    // backing (every BitmapFactory decode, every bitmap whose pixels native code wrote
    // through AndroidBitmap_lockPixels) used to be re-materialised by Canvas.bitmapAsImage
    // as a NEW BufferedImage filled by BufferedImage.setRGB -- which walks the ColorModel
    // one pixel at a time -- on EVERY draw. Telegram's MotionBackgroundDrawable draws its
    // 1636x3362 wallpaper pattern every frame, so that copy alone was ~1/3 of a 180 ms
    // frame (JFR, docs/mutex-fair-boot-window-retired_2026-09-29.md sec. 4). AOSP has no such
    // copy: Skia samples the bitmap's own pixel memory (SkBitmap -> SkPixmap) in place.
    // The view wraps the SAME int[] (DataBufferInt over the array, DirectColorModel with the
    // TYPE_INT_ARGB masks, non-premultiplied), so BufferedImage.getType() is TYPE_INT_ARGB,
    // Java2D picks the same IntArgb loops, and every pixel is the value setRGB stored (for
    // TYPE_INT_ARGB setRGB is a value-identity write). It aliases the live buffer, so a later
    // setPixel / native write / decode-into shows through exactly as a per-draw rebuild did;
    // a REPLACED buffer (new array) or a size change rebuilds the view. The external array
    // makes the DataBuffer UNTRACKABLE, so Java2D never caches a stale copy of it.
    // MACAPK_BITMAP_ARGB_VIEW=0 restores the per-draw setRGB copy. Gate: debug/argbview_gate.sh.
    private BufferedImage argbView;
    private int[] argbViewSrc;
    static final boolean ARGB_VIEW = !"0".equals(System.getenv("MACAPK_BITMAP_ARGB_VIEW"));
    private static final java.awt.image.DirectColorModel ARGB_CM =
        (java.awt.image.DirectColorModel) java.awt.image.ColorModel.getRGBdefault();
    /** Internal (Canvas draw path): a TYPE_INT_ARGB view that SHARES argbBuffer's storage,
     *  or null when there is no argbBuffer. See ARGB_VIEW above. */
    public BufferedImage macapkArgbView() {
        final int[] a = argbBuffer;
        final int w = width, h = height;
        if (a == null || w <= 0 || h <= 0 || a.length < w * h) return null;
        BufferedImage v = argbView;
        if (v != null && argbViewSrc == a && v.getWidth() == w && v.getHeight() == h) return v;
        java.awt.image.DataBufferInt db = new java.awt.image.DataBufferInt(a, w * h);
        java.awt.image.WritableRaster r = java.awt.image.Raster.createPackedRaster(db, w, h, w,
            new int[] { ARGB_CM.getRedMask(), ARGB_CM.getGreenMask(), ARGB_CM.getBlueMask(),
                        ARGB_CM.getAlphaMask() }, null);
        v = new BufferedImage(ARGB_CM, r, false, null);
        argbView = v;
        argbViewSrc = a;
        return v;
    }

    /** Copy the AWT-rendered pixels into argbBuffer. Called lazily
     *  whenever someone asks for the ARGB int[] (e.g. GL upload via
     *  getPixels, or Cocos2dxBitmap.getPixels). No-op when awtImage
     *  is null (the common AndEngine case). */
    /** Internal: push argbBuffer BACK into the AWT image -- the inverse of
     *  macapkExtractArgbFromAwt().
     *
     *  Needed by the NDK bitmap API (AndroidBitmap_unlockPixels in
     *  runtime/translator/src/bionic_shim.cpp). Guest native code locks a
     *  bitmap, writes pixels directly into this int[], and unlocks. For a
     *  bitmap with no awtImage that is already the live store and nothing more
     *  is required -- but for an AWT-backed bitmap (a Canvas drawing target)
     *  argbBuffer is a DERIVED CACHE, and the next macapkExtractArgbFromAwt()
     *  would overwrite the native writes with stale pixels from the
     *  BufferedImage. Same hazard, and the same fix, as the setPixels path
     *  above ("keep an AWT-backed bitmap in step").
     *
     *  Safe to call when there is no awtImage: it is then a no-op. */
    public void macapkCommitArgbToAwt() {
        if (awtImage != null && argbBuffer != null && width > 0 && height > 0) {
            awtImage.setRGB(0, 0, width, height, argbBuffer, 0, width);
        }
        // The NDK unlock's "pixels changed" commit (jnigraphics bitmap.cpp:78,
        // AndroidBitmap_unlockPixels -> notifyPixelsChanged). Bumped even with no
        // AWT backing: the guest wrote argbBuffer, which IS this bitmap's pixels.
        macapkNotifyPixelsChanged();
    }

    public void macapkExtractArgbFromAwt() {
        if (awtImage == null) return;
        if (argbBuffer == null || argbBuffer.length != width * height) {
            argbBuffer = new int[width * height];
            argbView = null; argbViewSrc = null;   // the view wraps the REPLACED array (see ARGB_VIEW)
        }
        // PERF (2026-08-18 lag/smear lane): BufferedImage.getRGB walks the image
        // ONE PIXEL AT A TIME through the ColorModel (OpenJDK:
        // BufferedImage.getRGB -> colorModel.getRGB(raster.getDataElements(..))
        // per pixel). Measured on the 808x1796 frame bitmap: 12.2 ms PER CALL,
        // and the frame path reads the buffer twice per presented frame (the
        // status-bar backdrop sample + the present) -- ~24 ms of a 16.7 ms
        // budget, the single largest term behind the owner's "laggy when going
        // to new tabs" report. For TYPE_INT_ARGB the raster's backing int[] IS
        // the packed 0xAARRGGBB pixels (getRGB is a value-identity read on that
        // type -- DirectColorModel, non-premultiplied, standard masks), so
        // bulk-copy the backing directly: byte-identical output, ~20x cheaper.
        // Guarded to the exact stock layout; anything else (subimage, custom
        // sample model, other types) keeps the general path below. getData()
        // marks the raster un-accelerable, which costs nothing here: these
        // images are only ever software-rendered into and read back.
        int[] data = macapkStockArgbBacking();
        if (data != null) {
            System.arraycopy(data, 0, argbBuffer, 0, width * height);
            return;
        }
        awtImage.getRGB(0, 0, width, height, argbBuffer, 0, width);
    }

    /** The AWT image's backing int[] when it has the exact stock
     *  TYPE_INT_ARGB layout (one bank, offset 0, scanline stride == width,
     *  raster == bitmap size), else null. On that layout the backing int[]
     *  IS the packed 0xAARRGGBB pixels, row-major with no padding -- the
     *  guard macapkExtractArgbFromAwt's bulk copy relies on. */
    private int[] macapkStockArgbBacking() {
        BufferedImage img = awtImage;
        if (img == null || img.getType() != BufferedImage.TYPE_INT_ARGB) return null;
        java.awt.image.WritableRaster r = img.getRaster();
        java.awt.image.DataBuffer db = r.getDataBuffer();
        java.awt.image.SampleModel sm = r.getSampleModel();
        if (db instanceof java.awt.image.DataBufferInt
                && db.getOffset() == 0 && db.getNumBanks() == 1
                && sm instanceof java.awt.image.SinglePixelPackedSampleModel
                && ((java.awt.image.SinglePixelPackedSampleModel) sm)
                       .getScanlineStride() == width
                && r.getWidth() == width && r.getHeight() == height) {
            int[] data = ((java.awt.image.DataBufferInt) db).getData();
            if (data.length >= width * height) return data;
        }
        return null;
    }

    /** Internal (the Canvas present path): the LIVE pixels of an AWT-backed
     *  bitmap, with no copy -- or null, and the caller falls back to
     *  macapkArgbBuffer().
     *
     *  WHY (2026-09-25, docs/canvas-present-cost-at-full-screen_2026-09-25.md):
     *  macapkArgbBuffer() bulk-copies the whole backing into argbBuffer, and
     *  ViewCanvasDriver called it twice per presented frame -- once to read ONE
     *  row for the status-bar backdrop, once to hand the frame to the native
     *  present, which copies it again. At the 3360x1796 full-screen window that
     *  is two 24 MB copies per frame whose only reader is synchronous code on
     *  the drawing thread itself, so the live array serves both reads
     *  byte-for-byte.
     *
     *  The contract a caller takes on: READ ONLY, and only on the thread that
     *  draws into this bitmap, before it draws again -- the array is the
     *  canvas's own store, so a write goes straight to the pixels and a
     *  retained reference sees every later draw. Exact length (== width *
     *  height) is required so `pixels.length` loops read the same range the
     *  copied buffer had. argbBuffer is left untouched: every reader of it
     *  (getPixel, getPixels, copyPixelsToBuffer) re-extracts first.
     *
     *  BISECT GATE, default ON: MACAPK_PRESENT_LIVE_ARRAY=0 (or
     *  MACAPK_PRESENT_FASTPATH=0, every present fast-path step at once) makes
     *  this always null, so every caller takes its copying fallback. The gate
     *  lives HERE rather than at each call site so a new caller cannot forget
     *  it. Victim measured on F-Droid at the full-screen window: bar phase
     *  0.68-0.83 ms (0.112 ms/Mpx: the copy) and ~0.6 ms of the 6.6-7.0 ms
     *  present; a bare-JVM System.arraycopy of the 6.03M-int frame is 0.87 ms. */
    public int[] macapkLiveArgbOrNull() {
        if (!LIVE_ARGB) return null;
        int[] data = macapkStockArgbBacking();
        return (data != null && data.length == width * height) ? data : null;
    }
    private static final boolean LIVE_ARGB =
            !"0".equals(System.getenv("MACAPK_PRESENT_FASTPATH"))
            && !"0".equals(System.getenv("MACAPK_PRESENT_LIVE_ARRAY"));

    /** Pixel readback. AndEngine uses this on the texture-upload path
     *  to feed the GLES20 glTexImage2D call. We copy out of the
     *  internal ARGB buffer; if it's null (no decode happened), the
     *  destination is zero-filled. */
    /** The precision a bitmap loses because its pixels are STORED in its
     *  Config's own format, not as 32-bit ARGB. Our backing store is always an
     *  int[] (see this file's header), so the loss has to be modelled on the
     *  way OUT or getPixels() reports colours the bitmap could not hold:
     *   - RGB_565 keeps 5/6/5 bits and has no alpha channel at all, so
     *     0xffff8000 reads back 0xffff8200 and a never-written 565 bitmap reads
     *     OPAQUE BLACK rather than transparent (BMP.state.erase.565,
     *     BMP.pix.acrossCfg.565, BMP.state.init.565).
     *   - ALPHA_8 keeps ONLY alpha; SkColorSetA(0, a) is what a read returns,
     *     so an erase to 0xffff8000 reads back 0xff000000
     *     (BMP.pix.acrossCfg.alpha8) and copy(ALPHA_8) of a fixture with one
     *     transparent quadrant collapses to exactly two values
     *     (BMP.copy.toAlpha8.hist).
     *   - ARGB_4444 keeps one nibble per channel.
     *  The arithmetic is the same pack/unpack pair copyPixelsToBuffer and
     *  copyPixelsFromBuffer already use (Skia's SkR16ToR32 bit replication), so
     *  the two paths cannot disagree, and it is IDEMPOTENT -- which is what
     *  lets sameAs() apply it to both sides without double-quantizing.
     *  ARGB_8888 and RGBA_F16 are exact for an 8-bit-per-channel value and are
     *  deliberately left alone, so the common path costs one enum compare.
     *  ⚠ Applied on READ only. macapkArgbBuffer() -- what the GL upload path
     *  and Canvas read -- still sees full precision, so this changes what an
     *  app MEASURES without re-quantizing what we DRAW. */
    private int macapkQuantizeForConfig(int argb) {
        if (config == Config.RGB_565) {
            int r = (argb >> 19) & 0x1f, g = (argb >> 10) & 0x3f, b = (argb >> 3) & 0x1f;
            return 0xff000000 | (((r << 3) | (r >> 2)) << 16)
                              | (((g << 2) | (g >> 4)) << 8)
                              |  ((b << 3) | (b >> 2));
        }
        if (config == Config.ALPHA_8) {
            return argb & 0xff000000;
        }
        if (config == Config.ARGB_4444) {
            int a = (argb >>> 28) & 0xf, r = (argb >> 20) & 0xf;
            int g = (argb >> 12) & 0xf,  b = (argb >> 4) & 0xf;
            return (((a << 4) | a) << 24) | (((r << 4) | r) << 16)
                 | (((g << 4) | g) << 8)  |  ((b << 4) | b);
        }
        return argb;
    }
    public void getPixels(int[] pixels, int offset, int stride,
                          int x, int y, int w, int h) {
        checkRecycled("Can't call getPixels() on a recycled bitmap");
        checkHardware("unable to getPixels(), "
                + "pixel access is not supported on Config#HARDWARE bitmaps");
        // AOSP returns BEFORE any validation for an empty region
        // (Bitmap.java:1970-1972), which is why a zero-width read with a
        // negative offset and an impossible stride must NOT throw -- and why
        // setPixels, whose mutability check runs first, DOES throw on the same
        // arguments (BMP.pix.gp.zeroWidth vs BMP.pix.sp.immutableZeroWidth).
        if (w == 0 || h == 0) return;
        checkPixelsAccess(x, y, w, h, offset, stride, pixels);
        if (awtImage != null) macapkExtractArgbFromAwt();
        // The 8888/F16 fast path is kept deliberately: this method is on the
        // per-frame overlay-present path (WindowOverlayStack reads the whole
        // surface every frame) and quantization is a no-op for those configs,
        // so it must not cost a per-pixel call there.
        final boolean exact = !macapkConfigQuantizes();
        for (int row = 0; row < h; ++row) {
            int srcStart = (y + row) * width + x;
            int dstStart = offset + row * stride;
            if (exact) {
                if (argbBuffer != null && srcStart >= 0 && srcStart + w <= argbBuffer.length) {
                    System.arraycopy(argbBuffer, srcStart, pixels, dstStart, w);
                } else {
                    java.util.Arrays.fill(pixels, dstStart, dstStart + w, 0);
                }
                continue;
            }
            for (int col = 0; col < w; ++col) {
                int src = srcStart + col;
                int v = (argbBuffer != null && src >= 0 && src < argbBuffer.length)
                        ? argbBuffer[src] : 0;
                pixels[dstStart + col] = macapkQuantizeForConfig(v);
            }
        }
    }
    private boolean macapkConfigQuantizes() {
        return config == Config.RGB_565 || config == Config.ALPHA_8
                || config == Config.ARGB_4444;
    }
    public byte[] macapkAlphaBytes() {
        int n = width * height;
        byte[] bytes = new byte[n];
        int[] src = macapkArgbBuffer();
        if (src != null) {
            for (int i = 0; i < n && i < src.length; i++) {
                bytes[i] = (byte) ((src[i] >>> 24) & 0xff);
            }
        }
        return bytes;
    }
    public int getPixel(int x, int y) {
        checkRecycled("Can't call getPixel() on a recycled bitmap");
        checkHardware("unable to getPixel(), "
                + "pixel access is not supported on Config#HARDWARE bitmaps");
        checkPixelAccess(x, y);
        if (awtImage != null) macapkExtractArgbFromAwt();
        int idx = y * width + x;
        int v = (argbBuffer != null && idx >= 0 && idx < argbBuffer.length)
                ? argbBuffer[idx] : 0;
        return macapkQuantizeForConfig(v);
    }

    /** AOSP Bitmap.setPixel(x,y,color): write a single ARGB pixel. The
     *  comment in this file's header claims setPixels exists, but the
     *  methods were never present -> AndEngine's createBitmap+setPixels
     *  sprite path and any in-memory image synthesis NoSuchMethodError'd.
     *  Backs the int[] buffer (allocating it lazily for createBitmap
     *  bitmaps); for AWT-backed bitmaps also writes through to the image. */
    public void setPixel(int x, int y, int color) {
        // AOSP: `if (!isMutable()) { throw new IllegalStateException(); }`
        // (Bitmap.java:3403-3421, "@throws IllegalStateException if the bitmap is
        // not mutable"). This used to enforce only the HARDWARE half of the rule,
        // with the note that general mutability enforcement was "a separate,
        // wider behavior change" -- but it IS the documented contract, and it is
        // an answer apps read: catch(IllegalStateException) { bm = bm.copy(...) }
        // is a standard idiom, and one that silently succeeds instead of throwing
        // leaves the app writing into a bitmap it believes it re-copied.
        // MEASURED (RAS.bmpapi.fromColors.setPixelThrows): the emulator answers
        // THROW:java.lang.IllegalStateException, we answered NOTHROW.
        // isMutable() already reports false for Config.HARDWARE, so this
        // subsumes the old check.
        checkRecycled("Can't call setPixel() on a recycled bitmap");
        if (!isMutable()) throw new IllegalStateException();
        // AOSP's ORDER: mutability BEFORE bounds (Bitmap.java:2050-2056). So
        // setPixel(-1, 0) is IllegalStateException on an immutable bitmap and
        // IllegalArgumentException on a mutable one -- identical arguments,
        // different exception (BMP.pix.set.immutable.negative vs
        // set.mutable.negative). We silently returned for out-of-range
        // coordinates, which loses an app's own indexing bug.
        checkPixelAccess(x, y);
        ensureArgbBuffer();
        int stored = macapkPremulRoundTrip(color);
        argbBuffer[y * width + x] = stored;
        if (awtImage != null) awtImage.setRGB(x, y, stored);
        macapkNotifyPixelsChanged();   // SkBitmap::writePixels notifies (hwui Bitmap_setPixel)
    }

    /** The precision an ARGB_8888 bitmap loses because its pixels are stored
     *  PREMULTIPLIED. AOSP's setPixel takes "a non-premultiplied ARGB value"
     *  (Bitmap.java:3403) and getPixel returns "a non-premultiplied ARGB value"
     *  (Bitmap.java:3308), and the value in between is premultiplied -- so the
     *  round trip is lossy at low alpha and every real device answers the lossy
     *  value. MEASURED (RAS.bmpapi.setGetPixel.lowAlpha): setPixel(0x01020304)
     *  then getPixel answers 0x01000000 on the emulator (alpha 1 cannot carry
     *  colour) against 0x01020304 here.
     *
     *  The arithmetic is Skia's, exactly -- and the two halves of the round trip
     *  do NOT take the same code path, which is the whole reason the answer is
     *  asymmetric:
     *
     *  WRITE. Bitmap_setPixel builds a 1x1 BGRA8888/kUnpremul source pixmap and
     *  calls SkBitmap::writePixels, i.e. SkConvertPixels with steps.flags =
     *  {premul}. swizzle_or_premul's guard rejects only linearize / gamut /
     *  unpremul / encode (external/skia @ android11-release,
     *  src/core/SkConvertPixels.cpp:40-47), so this INTEGER fast path is taken:
     *      SkOpts::RGBA_to_rgbA -> div255_round(vmull_u8(c, a))
     *          = vraddhn_u16(x, vrshrq_n_u16(x, 8)) = (x + ((x+128)>>8) + 128) >> 8
     *  (src/opts/SkSwizzler_opts.h) which is bit-for-bit SkMulDiv255Round, below.
     *
     *  READ. Bitmap_getPixel reads into a BGRA8888/kUnpremul destination
     *  (frameworks/base libs/hwui/jni/Bitmap.cpp:871-883), so steps.flags.unpremul
     *  is set and the SAME guard now REJECTS the fast path -> convert_with_pipeline
     *  -> the FLOAT raster pipeline:
     *      load_8888   c = byte * (1/255.0f)
     *      unpremul    invA = isfinite(1/a) ? 1/a : 0; c *= invA
     *                  (src/core/SkColorSpaceXformSteps.cpp, apply())
     *      store_8888  to_unorm(c,255) = round(min(max(0,c),1.0f) * 255)
     *                  with round = vcvtnq_u32_f32, i.e. ties-to-EVEN
     *                  (src/opts/SkRasterPipeline_opts.h:211, :1264-1268)
     *
     *  We used to un-premultiply with Skia's INTEGER helper instead
     *  (SkUnPreMultiply::ApplyScale(scale,c) = (scale*c + (1<<23)) >> 24, scale =
     *  (255<<24)/a). That helper exists in Skia but is NOT on this path, and its
     *  >>24 truncates where the pipeline rounds: a=254, c=128 premultiplies to 127
     *  under both rules, then un-premultiplies to trunc(127.99999976) = 127 = 0x7f
     *  rather than rint(127.500) = 128 = 0x80 (BMP.lossy.premul.afe, ref feff8040).
     *  MEASURED over the whole (a,c) space: 1162 of 65024 round trips move, each by
     *  exactly 1, and the four passing premul.* rows plus
     *  RAS.bmpapi.setGetPixel.lowAlpha are unchanged.
     *
     *  Alpha 255 and alpha 0 are handled directly: 255 round-trips exactly, and
     *  premultiplying by 0 destroys the colour, which is what a real premultiplied
     *  store does.
     *
     *  ⚠ DELIBERATELY NOT APPLIED TO setPixels. Our backing store is
     *  NON-premultiplied end to end (BufferedImage.TYPE_INT_ARGB), so modelling
     *  the loss on a bulk path would zero the RGB of every fully transparent
     *  pixel WITHOUT the premultiplied compositing that makes that harmless on a
     *  device -- Java2D's bilinear filtering would then bleed black in from those
     *  texels and put dark fringes around every scaled sprite. setPixel is a
     *  per-pixel API where the app can observe the round trip; setPixels is a
     *  bulk upload path where the observable cost would be a rendering artifact
     *  real Android does not have. */
    private static int macapkPremulRoundTrip(int argb) {
        int a = (argb >>> 24) & 0xff;
        if (a == 0xff) return argb;
        if (a == 0) return 0;
        int r = macapkUnpremul(macapkMulDiv255Round((argb >>> 16) & 0xff, a), a);
        int g = macapkUnpremul(macapkMulDiv255Round((argb >>>  8) & 0xff, a), a);
        int b = macapkUnpremul(macapkMulDiv255Round( argb         & 0xff, a), a);
        return (a << 24) | (r << 16) | (g << 8) | b;
    }
    /** The WRITE half: Skia's div255_round, reached through swizzle_or_premul.
     *  Integer, and deliberately so -- see the contract above. */
    private static int macapkMulDiv255Round(int c, int a) {
        int prod = c * a + 128;
        return (prod + (prod >> 8)) >> 8;
    }
    /** The READ half: the float raster pipeline's unpremul + store_8888. Written
     *  in float (not double) because the pipeline is float32 throughout, and the
     *  associativity matters: `(p/255) * (1/(a/255))` is not `p*255/a` in float32,
     *  and MEASURED they disagree on 354 of the 65024 (a,c) pairs. */
    private static int macapkUnpremul(int c, int a) {
        float av = a * (1f / 255f);
        float inv = 1f / av;
        // Skia's own is_finite: "I don't know why isfinite(x) stopped working on
        // the Chromecast bots" (SkColorSpaceXformSteps::apply). a == 0 never
        // reaches here, but keep the guard so the shape matches the source.
        if (!(inv * 0f == 0f)) inv = 0f;
        float v = (c * (1f / 255f)) * inv;
        if (v < 0f) v = 0f;
        if (v > 1f) v = 1f;
        // vcvtnq_u32_f32 is round-to-nearest, ties to EVEN. Math.round would be
        // ties-away, and MEASURED the two disagree on 1000 (a,c) pairs.
        return (int) Math.rint((double) (v * 255f));
    }

    /** AOSP Bitmap.setPixels(pixels, offset, stride, x, y, width, height). */
    public void setPixels(int[] pixels, int offset, int stride,
                          int x, int y, int w, int h) {
        // Same documented contract as setPixel: AOSP throws IllegalStateException
        // on an immutable bitmap. isMutable() covers the HARDWARE case too.
        checkRecycled("Can't call setPixels() on a recycled bitmap");
        if (!isMutable()) throw new IllegalStateException();
        // ⚠ The mutability check sits ABOVE the empty-region early return here
        // and BELOW it in getPixels -- that asymmetry is AOSP's, and it is the
        // reason a zero-width setPixels on an immutable bitmap throws where the
        // same call on getPixels does not (BMP.pix.sp.immutableZeroWidth).
        if (w == 0 || h == 0) return;
        checkPixelsAccess(x, y, w, h, offset, stride, pixels);
        ensureArgbBuffer();
        for (int row = 0; row < h; ++row) {
            int dy = y + row;
            if (dy < 0 || dy >= height) continue;
            int srcStart = offset + row * stride;
            int dstStart = dy * width + x;
            int copy = Math.min(w, width - x);
            if (copy > 0 && srcStart >= 0 && srcStart + copy <= pixels.length
                && dstStart >= 0 && dstStart + copy <= argbBuffer.length) {
                System.arraycopy(pixels, srcStart, argbBuffer, dstStart, copy);
            }
        }
        if (awtImage != null) {
            awtImage.setRGB(0, 0, width, height, argbBuffer, 0, width);
        }
        // SkBitmap::writePixels notifies (hwui Bitmap_setPixels). The empty
        // region returned above without writing, and Skia's writePixels does not
        // notify for a rejected (empty) write either.
        macapkNotifyPixelsChanged();
    }

    private void ensureArgbBuffer() {
        if (argbBuffer == null || argbBuffer.length != width * height) {
            // The Config's zero-state, not bare zeroes: a 565 bitmap that an app
            // setPixel()s a corner of must read (and blit) BLACK everywhere
            // else, not transparent. See macapkZeroPixel.
            int[] fresh = macapkAllocBuffer();
            if (argbBuffer != null) {
                System.arraycopy(argbBuffer, 0,
                        fresh, 0, Math.min(argbBuffer.length, fresh.length));
            } else if (awtImage != null && width > 0 && height > 0) {
                awtImage.getRGB(0, 0, width, height, fresh, 0, width);
            }
            argbBuffer = fresh;
            argbView = null; argbViewSrc = null;   // the view wraps the REPLACED array (see ARGB_VIEW)
        }
    }

    /** AOSP getColor short-circuits to Color.valueOf(nativeGetPixel) when the
     *  colour space IS sRGB (Bitmap.java:1917-1938) -- so it inherits getPixel's
     *  recycled / hardware / bounds checks, which is why an out-of-bounds
     *  getColor is IllegalArgumentException and not a 0 (BMP.pix.getColor.oob,
     *  BMP.recycle.getColor). */
    public android.graphics.Color getColor(int x, int y) {
        return android.graphics.Color.valueOf(getPixel(x, y));
    }
    // AOSP setWidth/setHeight/setConfig (frameworks/base graphics/java/
    // android/graphics/Bitmap.java, verified against aosp-mirror main): all
    // three are documented as pure convenience wrappers around reconfigure()
    // with the other two dimensions held at their current value --
    // "Convenience method for calling reconfigure(int,int,Config) with the
    // current height/width/config". setWidth/setHeight were empty bodies (a
    // silent no-op: bitmap.setWidth(w) looked like it worked and did
    // nothing), and setConfig did not exist at all (a NoSuchMethodError for
    // any caller). reconfigure() below already carries the real behavior;
    // these just delegate to it exactly as AOSP does.
    public void setWidth(int width) { reconfigure(width, getHeight(), getConfig()); }
    public void setHeight(int height) { reconfigure(getWidth(), height, getConfig()); }
    public void setConfig(Config config) { reconfigure(getWidth(), getHeight(), config); }
}

