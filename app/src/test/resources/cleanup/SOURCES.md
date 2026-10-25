# Clean up test photos

Evaluation and regression fixtures for PdqTest and CleanupEvalTest. Unmodified downloads; the tests derive every copy,
crop, blur and edit from them in code. Not shipped in the app.

| File | Source | Licence | Used for |
|---|---|---|---|
| bridge-1-original.jpg | github.com/facebook/ThreatExchange, pdq/data/reg-test-input/dih/ (sha256 b5b07996...7298) | BSD, Copyright (c) Meta Platforms, Inc. and affiliates (licenses/pdq-BSD.txt) | PDQ reference hashes (pdq/java/reg_test/expected/out); copies; a made-up burst |
| iss040e108044.jpg, iss040e108050.jpg | images.nasa.gov, `~medium.jpg` of each id (ISS time-lapse from a fixed camera, 19:06:22.06 and 19:07:04.00, 1/40 s at ISO 2500) | NASA imagery, US government work, public domain | similar shots: same view 42 s apart, the person moved |
| KSC-20190913-PH_KLS01_0030.jpg, _0035.jpg | images.nasa.gov, `~medium.jpg` (Kim Shiflett, Pad 39B wet flow test, 10:43:05.90 and 10:43:12.55) | public domain, as above | same scene seconds apart but reframed: not copies, not similar |
| NHQ202403030019.jpg, NHQ202403030020.jpg | images.nasa.gov, `~medium.jpg` (Aubrey Gemignani, Crew-8 launch control; 21:22:46.46 and 21:23:06.39, ISO 2000 and 2500) | public domain, as above | same room, different people 20 s apart; a sharp face on a soft background (not blurry) |
| iss061e110433.jpg, iss061e110462.jpg | images.nasa.gov, `~medium.jpg` (ISS night shots, 1.3 s and 2 s at ISO 3200, six minutes apart) | public domain, as above | night: dark, alike, never copies; long exposures |

NASA media guidelines: https://www.nasa.gov/nasa-brand-center/images-and-media/ (no endorsement implied).
