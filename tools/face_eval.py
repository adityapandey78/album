#!/usr/bin/env python3
"""Fetches the People evaluation photos and puts them on a test phone or emulator, for FaceEvalTest.

    python3 tools/face_eval.py OUT_DIR            # downloads into OUT_DIR, then pushes to /sdcard/Pictures/PeopleEval/
    python3 tools/face_eval.py OUT_DIR --no-push

282 photos from NASA's image library (images.nasa.gov; US government works, public domain): 102 of 11 astronauts in
portraits, at work and in crowds, and 180 of crowds, children and groups with no astronaut named in the caption.
Never commit them. A file's name says who is in it (see truthOf in FaceEvalTest): Person_Name_N is that person
(the biggest face), Person_Name_N@62 the face across 62% of the width, x_N nobody known. Then run the test:

    ./gradlew -I tools/eval.init.gradle :app:assembleDebug :app:assembleDebugAndroidTest
    adb install -r -g app/build/outputs/apk/debug/app-debug.apk
    adb install -r app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
    adb shell am instrument -w -e class com.aditya.album.FaceEvalTest com.aditya.album.eval.test/androidx.test.runner.AndroidJUnitRunner
    adb shell cat /sdcard/Android/data/com.aditya.album.eval/files/face-eval.txt
"""
import os
import shutil
import subprocess
import sys
import time
import urllib.request

PHOTOS = """
Anne_McClain_0	NHQ202009160006
Anne_McClain_1	iss057e132459
Anne_McClain_2	jsc2025e012271
Anne_McClain_4	NHQ202001130001
Anne_McClain_5	jsc2025e015859
Anne_McClain_7	jsc2024e080755
Anne_McClain_18	jsc2024e066730_alt
Anne_McClain_20	iss058e010750
Anne_McClain_23@25	iss057e132469
Anne_McClain_28	iss073e0134965
Anne_McClain_31	iss073e0027808
Anne_McClain_43	NHQ202001140003
Chris_Cassidy_1	jsc2013e013362
Chris_Cassidy_2	jsc2020e002539_alt
Christina_Koch_0	NHQ202009160004
Christina_Koch_2	KSC-20210714-PH-GEB01_0036
Christina_Koch_4	KSC-20210714-PH-GEB01_0131
Christina_Koch_7	PIA23862
Christina_Koch_14	jsc2019e004068
Christina_Koch_20	iss059e035465
Christina_Koch_26	NHQ202305180019
Christina_Koch_27	art002e027803
Christina_Koch_29	art002e027808
Christina_Koch_31	jsc2026e396748
Christina_Koch_34@75	NHQ202305180024
Christina_Koch_37	NHQ202406050122
Christina_Koch_41@30	jsc2026e397471
Jeanette_Epps_4	iss071e414653
Jeanette_Epps_5	NHQ201910240038
Jeanette_Epps_6	iss071e522127
Jeanette_Epps_7	NHQ202503030015
Jeanette_Epps_8@65	NHQ202503030017
Jeanette_Epps_29@25	NHQ202503050015
Jeanette_Epps_37	NHQ201712160025
Jeanette_Epps_40@25	KSC-20240225-PH-KLS01_0152
Jeanette_Epps_42	KSC-20240225-PH-KLS01_0132
Jessica_Meir_0	NHQ202009150005
Jessica_Meir_1	jsc2025e078605_alt
Jessica_Meir_2	jsc2026e002317
Jessica_Meir_5	iss074e0573327
Jessica_Meir_11	jsc2026e002966
Jessica_Meir_14@70	iss062e024073
Jessica_Meir_17	iss074e0760518
Jessica_Meir_24@70	iss074e0437872
Jessica_Meir_25	iss074e0832632
Jessica_Meir_26	iss074e0832627
Jessica_Meir_30	jsc2026e002976
Jessica_Meir_32	iss062e039026
Jessica_Meir_39	iss074e0603697
Jessica_Meir_40	iss074e0541212
Kalpana_Chawla_0	sts087-308-023
Kalpana_Chawla_1	KSC-97PC-1655
Kalpana_Chawla_2	KSC-02pd1127
Kalpana_Chawla_5	S107E05001
Kalpana_Chawla_7	KSC-97PC1680
Kalpana_Chawla_17	sts087-357-019
Kalpana_Chawla_40	KSC-02pd1954
Kalpana_Chawla_43	KSC-02pd1976
Kalpana_Chawla_47	KSC-02pd1928
Kjell_Lindgren_0	NHQ202009160011
Kjell_Lindgren_8	NHQ202303300008
Kjell_Lindgren_16	jsc2015e053682
Kjell_Lindgren_20	iss045e168328
Kjell_Lindgren_21	NHQ201806020011
Kjell_Lindgren_26	iss045e035341
Kjell_Lindgren_30	iss045e035321
Kjell_Lindgren_31	iss045e035336
Kjell_Lindgren_32	KSC-20230322-PH-KLS08_0002
Kjell_Lindgren_34@20	NHQ202303310025
Peggy_Whitson_1	KSC-02pd0637
Peggy_Whitson_2	JSC2001-03044
Peggy_Whitson_6	jsc2007e045195
Raja_Chari_1	NHQ201907120006
Raja_Chari_2	NHQ201907120005
Raja_Chari_3	NHQ202009080001
Raja_Chari_5	jsc2017e114853
Raja_Chari_6	iss067e000137
Raja_Chari_7	NHQ202002100016
Raja_Chari_20@75	NHQ202002100012
Raja_Chari_40@15	NHQ202212070014
Sunita_Williams_1	jsc2005e02663
Sunita_Williams_2	jsc2012e096296
Sunita_Williams_4	jsc2012e096604
Victor_Glover_1	art002e041740
Victor_Glover_2	jsc2018e067775_alt
Victor_Glover_3	NHQ202009150002
Victor_Glover_6	KSC-20210715-PH-KLS02_0023
Victor_Glover_9@78	KSC-20241108-PH-JBS01_0065
Victor_Glover_13	KSC-20210715-PH-KLS02_0043
Victor_Glover_14	KSC-20210715-PH-KLS02_0074
Victor_Glover_15	KSC-20200814-PH-SPX01_0005
Victor_Glover_22@62	KSC-20241108-PH-KLS02_0120
Victor_Glover_29	AFRC2026-0177-42
Victor_Glover_37	NHQ202204280013
Victor_Glover_44	NHQ202204280015
x_1	KSC-03pd2970
x_2	KSC-03pd2969
x_3	KSC-03pd2961
x_4	AFRC2026-0177-53
x_5	NHQ202204280012
x_7	NHQ202212080008
x_9	KSC-20230322-PH-KLS08_0009
x_d1	SSC-20240622-s00763
x_d2	ED04-0056-078
x_d3	ED04-0056-079
x_d4	ED04-0056-076
x_d5	ED04-0056-075
x_d6	SSC-20100713-S01796
x_d7	KSC-2010-5448
x_d8	KSC-2010-5449
x_d9	201011010017HQ
x_d10	201010230002HQ
x_d11	MSFC-1701383
x_d12	MSFC-1701380
x_d13	MSFC-1701379
x_d14	MSFC-1701381
x_d15	KSC-2010-5414
x_d16	2013-1865
x_d17	KSC-2010-5432
x_d18	KSC-2010-5437
x_d19	KSC-2010-5421
x_d20	KSC-2010-5434
x_d21	KSC-2010-5433
x_d22	KSC-2010-5412
x_d23	KSC-2010-5442
x_d24	KSC-2010-5443
x_d25	2013-1866
x_d26	KSC-2010-5413
x_d27	KSC-2010-5417
x_d28	KSC-2010-5446
x_d29	KSC-2010-5411
x_d30	KSC-2010-5440
x_d31	KSC-20221006-PH-FJM01_0028
x_d32	KSC-20221006-PH-FJM01_0009
x_d33	KSC-20221006-PH-FJM01_0036
x_d34	KSC-20221006-PH-FJM01_0037
x_d35	KSC-20221006-PH-FJM01_0038
x_d36	KSC-20221006-PH-FJM01_0010
x_d37	KSC-20221006-PH-FJM01_0013
x_d38	KSC-20221006-PH-FJM01_0040
x_d39	NHQ202603190008
x_d40	KSC-20250428-PH-FMX01_0011
x_d41	KSC-20250428-PH-FMX01_0008
x_d42	NHQ202603190004
x_d43	NHQ202603190005
x_d44	NHQ202603190003
x_d45	NHQ202603190007
x_d46	NHQ202603190006
x_d47	NHQ202603190002
x_d48	NHQ202603190001
x_d49	MSFC-1800069
x_d50	KSC-03pd2406
x_d51	KSC-03pd2390
x_d52	KSC-03pd2405
x_d53	KSC-2012-2766
x_d54	GRC-2011-C-02336
x_d55	GRC-2011-C-02338
x_d56	200910290015HQ
x_d57	ARC-1991-A91-0102-21
x_d58	GSFC_20161219_2017-4383_022
x_d59	GSFC_20161219_2017-4383_036
x_d60	S96-04296
x_d61	KSC-2012-2768
x_d62	KSC-2012-2763
x_d63	KSC-2012-2767
x_d64	KSC-2012-2761
x_d65	KSC-2012-2760
x_d66	KSC-2012-2762
x_d67	KSC-2012-2765
x_d68	KSC-2012-2769
x_d69	MSFC-1800103
x_d70	MSFC-1800104
x_d71	MSFC-202100303
x_d72	201408250005HQ
x_d73	MSFC-202100306
x_d74	MSFC-202100311
x_d75	MSFC-202100309
x_d76	201408250008HQ
x_d77	201408250007HQ
x_d78	S86-26428
x_d79	PIA15413
x_d80	201408200010HQ
x_d81	20050414_update_02
x_d82	EC02-0273-6
x_d83	EC02-0273-5
x_d84	KSC-04pd0031
x_d85	MSFC-1500136
x_d86	MSFC-1500137
x_d87	MSFC-1500132
x_d88	MSFC-1500131
x_d89	MSFC-1500134
x_d90	MSFC-1500130
x_d91	MSFC-1500133
x_d92	MSFC-1500135
x_d93	1400881
x_d94	1400884
x_d95	S86-26437
x_d96	jsc2001-01006
x_d97	1400886
x_d98	1400887
x_d99	S86-26438
x_d100	1400885
x_d101	1400888
x_d102	1400883
x_d103	1400890
x_d104	1400889
x_d105	201405270035HQ
x_d106	KSC-2013-3531
x_d107	GSFC_20180203_2018-7006_039
x_d108	GSFC_20180203_2018-7006_027
x_d109	GSFC_20180203_2018-7006_032
x_d110	GSFC_20180203_2018-7006_040
x_d111	GRC-2018-C-05622
x_d112	ARC-2008-ACD08-0181-002
x_d113	ARC-2008-ACD08-0181-051
x_d114	ARC-2008-ACD08-0181-037
x_d115	ARC-2008-ACD08-0181-009
x_d116	ARC-2008-ACD08-0181-021
x_d117	ARC-2008-ACD08-0181-012
x_d118	ARC-2008-ACD08-0181-031
x_d119	ARC-2008-ACD08-0181-029
x_d120	ARC-2008-ACD08-0181-065
x_d121	ARC-2008-ACD08-0181-055
x_d122	ARC-2008-ACD08-0181-042
x_d123	ARC-2008-ACD08-0181-023
x_d124	ARC-2008-ACD08-0181-052
x_d125	ARC-2008-ACD08-0181-038
x_d126	ARC-2008-ACD08-0181-040
x_d127	ARC-2008-ACD08-0181-028
x_d128	ARC-2008-ACD08-0181-045
x_d129	ARC-2008-ACD08-0181-018
x_d130	ARC-2008-ACD08-0181-010
x_d131	ARC-2008-ACD08-0181-050
x_d132	ARC-2008-ACD08-0181-062
x_d133	ARC-2008-ACD08-0181-008
x_d134	ARC-2008-ACD08-0181-044
x_d135	ARC-2008-ACD08-0181-005
x_d136	ARC-2008-ACD08-0181-046
x_d137	ARC-2008-ACD08-0181-017
x_d138	ARC-2008-ACD08-0181-049
x_d139	ARC-2008-ACD08-0181-006
x_d140	ARC-2008-ACD08-0181-067
x_d141	ARC-2008-ACD08-0181-016
x_d142	ARC-2008-ACD08-0181-054
x_d143	ARC-2008-ACD08-0181-015
x_d144	ARC-2008-ACD08-0181-047
x_d145	ARC-2008-ACD08-0181-020
x_d146	ARC-2008-ACD08-0181-041
x_d147	KSC-03PD-1130
x_d148	KSC-03PD-1131
x_d149	1001549
x_d150	AFRC2024-0086-45
x_d151	ACD17-0001-007
x_d152	ARC-1989-AC89-0276-6
x_d153	2013-2455
x_d154	NHQ202107280006
x_d155	NHQ202107280007
x_d156	AFRC2026-0206-01
x_d157	KSC-2009-5401
x_d158	KSC-2009-5400
x_d159	KSC-2009-5402
x_d160	GRC-2024-C-05403
x_d161	GRC-2024-C-05419
x_d162	2013-2451
x_d163	2013-2454
x_d164	2013-2452
x_d165	2013-2450
x_d166	2013-2453
x_d167	NHQ202610060015
x_d168	NHQ202610050014
x_d169	KSC-2010-4742
x_d170	2011-7220
x_d171	KSC-2010-4743
x_d172	AFRC2024-0086-19
x_d173	KSC-20190710-PH_JBS01_0009
x_d174	KSC-20190710-PH_JBS01_0002
x_d175	2011-7219
x_d176	2011-7218
x_d177	KSC-2012-1545
x_d178	0300845
x_d179	200623_ISS_1
x_d180	international-space-station-mockup-training_52233924879_o
"""


def adb():
    sdk = os.environ.get("ANDROID_HOME", os.path.expanduser("~/Library/Android/sdk"))
    return shutil.which("adb") or os.path.join(sdk, "platform-tools", "adb")


def main():
    if len(sys.argv) < 2:
        sys.exit(__doc__)
    out = sys.argv[1]
    os.makedirs(out, exist_ok=True)
    for line in PHOTOS.split("\n"):
        if not line:
            continue
        name, nasa_id = line.split("\t")
        path = os.path.join(out, name + ".jpg")
        if os.path.exists(path):
            continue
        for size in ("medium", "large", "orig"):  # a few photos have only some renditions
            try:
                url = f"https://images-assets.nasa.gov/image/{nasa_id}/{nasa_id}~{size}.jpg"
                data = urllib.request.urlopen(url, timeout=60).read()
                with open(path, "wb") as f:
                    f.write(data)
                break
            except OSError:
                pass
        else:
            print("not found:", nasa_id)
    if "--no-push" in sys.argv:
        return
    subprocess.run([adb(), "push", out + "/.", "/sdcard/Pictures/PeopleEval/"], check=True, stdout=subprocess.DEVNULL)
    # Files pushed over adb can stay pending (hidden from apps) until MediaStore scans the volume
    subprocess.run([adb(), "shell", "content", "call", "--uri", "content://media", "--method", "scan_volume", "--arg", "external_primary"], check=True, stdout=subprocess.DEVNULL)
    time.sleep(3)


if __name__ == "__main__":
    main()
