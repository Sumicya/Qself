# NT hook-target scanner

`qself-scan.py` reads the host APK's dex files **on the device** and prints the
exact methods and fields of the classes Qself needs to hook. Class names are not
enough for that — a hook needs the method name and parameter types, and on a
real device those only exist inside the APK (see `docs/NT-ADAPTATION.md`).

```bash
# 1. pull the host APK out (root)
su -c 'cp "$(pm path com.tencent.mobileqq | head -1 | cut -d: -f2)" /sdcard/qq-base.apk'

# 2. extract the dex images
mkdir -p ~/qqdex && cd ~/qqdex && unzip -o /sdcard/qq-base.apk 'classes*.dex' >/dev/null

# 3. scan (defaults cover qfix / feedback / bugly / qqnt.startup)
python3 ~/Qself/tools/nt-scan/qself-scan.py ~/qqdex

# narrower second pass, comma separated — dotted or slash form both work
python3 ~/Qself/tools/nt-scan/qself-scan.py ~/qqdex com.tencent.mobileqq.qfix.,com.tencent.qqnt.aio.
```

Output: candidate entry points on stdout, full dump in `/sdcard/qself-methods.txt`
(falls back to the current directory). No dependencies beyond Python 3, nothing
is uploaded.

Other modes:

```bash
python3 qself-scan.py --all  ~/qqdex           # every class, no prefix filter
python3 qself-scan.py --diag ~/qqdex/classes12.dex   # header + map_list + bad pointers
```

A class whose member table cannot be decoded is **skipped and reported**, not
fatal: `--diag` then tells you whether the image is malformed (a `class_data_off`
landing in the wrong section) or the file is truncated.

**Do not commit the dex files (or the APK) to this repository.** They are
Tencent's proprietary code: publishing them is a licence violation, a single
APK is 300+ MB (GitHub's per-file limit is 100 MB), and it would bloat every
clone. Method signatures are the only thing needed here, and they are text.

## Validation

The parser is cross-checked against androguard on real and generated dex images
(kept outside the repo). That check caught two bugs which produced
plausible-looking but wrong output instead of an error:

| symptom | cause |
| --- | --- |
| every class name wrong, prefixes never match | `class_idx` from `class_defs` was used as a `string_ids` index — it indexes `type_ids`, which then indexes `string_ids` |
| `struct.error` mid-dump on real APKs | the four arrays of `class_data_item` are diff-coded **independently**; carrying the index across a group boundary walks past the end of `method_ids` |

Both are fixed here and in the in-app reader (`ui/DexReader.kt`), which had the
same two mistakes plus a swapped `field_ids_size`/`field_ids_off` pair.
