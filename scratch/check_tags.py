import sys
try:
    from mutagen.flac import FLAC
    audio = FLAC("scratch/06. Labrinth - Nate Growing Up.flac")
    print("Pictures count:", len(audio.pictures))
    for i, pic in enumerate(audio.pictures):
        print(f"Picture {i}: mime={pic.mime}, type={pic.type}, desc={pic.desc}, size={len(pic.data)} bytes")
except Exception as e:
    print("Error:", e)
