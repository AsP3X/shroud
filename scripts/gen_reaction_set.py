"""Generate the reaction set and its keyword table for the web (TS) and iOS (Swift).

    python3 scripts/gen_reaction_set.py

writes web/src/reactionSet.ts and ios/shroud/Services/Messaging/ReactionSet.swift. The quick seven
come first, then the rest of Telegram's standard set (so the first rows keep their places), then a
broad curated set — smileys, gestures, hearts, animals, food, celebration and symbols — named from
Unicode (Python's unicodedata; Python 3.13+ for Unicode 15.1+) with hand-written synonyms on top.
Both clients' fuzzy search (reactionSearch.ts / ReactionSearch.swift) runs over these tables.
"""
import json, pathlib, re, unicodedata

ROOT = pathlib.Path(__file__).resolve().parents[1]

QUICK = ["❤️", "🔥", "👍", "😢", "🙏", "😮", "👎"]
TELEGRAM = [
    "🥰", "👏", "😁", "🤔", "🤯", "😱", "🤬", "🎉", "🤩", "🤮", "💩", "👌", "🕊️", "🤡",
    "🥱", "🥴", "😍", "🐳", "❤️‍🔥", "🌚", "🌭", "💯", "🤣", "⚡", "🍌", "🏆", "💔", "🤨",
    "😐", "🍓", "🍾", "💋", "🖕", "😈", "😴", "😭", "🤓", "👻", "👨‍💻", "👀", "🎃", "🙈",
    "😇", "😨", "🤝", "✍️", "🤗", "🫡", "🎅", "🎄", "☃️", "💅", "🤪", "🗿", "🆒", "💘",
    "🙉", "🦄", "😘", "💊", "🙊", "😎", "👾", "🤷", "😡",
]

# Hand-written words people type, on top of the Unicode name.
SYNONYMS = {
    "❤️": ["heart", "love", "like"], "🔥": ["lit", "hot", "flame"],
    "👍": ["like", "yes", "ok", "approve", "agree", "good"], "😢": ["sad", "tear", "cry"],
    "🙏": ["please", "thanks", "thank you", "pray", "high five"], "😮": ["wow", "surprised", "shocked", "omg"],
    "👎": ["dislike", "no", "disagree", "bad"], "🥰": ["adore", "love", "in love", "crush"],
    "👏": ["applause", "bravo", "clap", "well done"], "😁": ["grin", "happy", "smile", "teeth"],
    "🤔": ["hmm", "think", "wonder"], "🤯": ["mind blown", "wow"], "😱": ["scream", "shocked", "horror"],
    "🤬": ["cursing", "swearing", "angry", "rage"], "🎉": ["celebrate", "congratulations", "tada", "party"],
    "🤩": ["starstruck", "excited", "amazing", "wow"], "🤮": ["vomit", "puke", "sick", "gross"],
    "💩": ["poop", "shit", "crap"], "👌": ["okay", "perfect", "nice"], "🕊️": ["peace", "bird"],
    "🤡": ["clown", "joke", "fool"], "🥱": ["yawn", "bored", "tired", "sleepy"], "🥴": ["drunk", "dizzy", "tipsy"],
    "😍": ["heart eyes", "love", "adore"], "🐳": ["whale", "sea"], "❤️‍🔥": ["burning heart", "passion", "love"],
    "🌚": ["moon", "dark", "creepy"], "🌭": ["sausage", "food"], "💯": ["100", "perfect score", "keep it 100"],
    "🤣": ["rofl", "lol", "laugh", "haha", "hilarious"], "⚡": ["lightning", "zap", "electric", "thunder"],
    "🍌": ["fruit"], "🏆": ["winner", "champion", "award", "cup"], "💔": ["heartbreak", "sad"],
    "🤨": ["suspicious", "skeptical", "doubt", "hmm"], "😐": ["meh", "blank", "straight face"],
    "🍓": ["fruit", "berry"], "🍾": ["champagne", "celebrate", "cheers"], "💋": ["kiss", "lips"],
    "🖕": ["fuck you", "flip off", "rude"], "😈": ["devil", "evil", "naughty"], "😴": ["sleep", "zzz", "tired", "snore"],
    "😭": ["sob", "cry", "bawling", "sad", "tears"], "🤓": ["nerd", "geek", "glasses"], "👻": ["boo", "spooky", "halloween"],
    "👨‍💻": ["coder", "developer", "programmer", "hacker", "computer"], "👀": ["look", "watching", "see", "side eye"],
    "🎃": ["pumpkin", "halloween"], "🙈": ["monkey", "hide", "embarrassed", "cover eyes"],
    "😇": ["angel", "innocent", "holy"], "😨": ["scared", "afraid", "fear", "anxious"],
    "🤝": ["deal", "agreement", "thanks", "partners"], "✍️": ["write", "note", "pen"], "🤗": ["hug", "warm"],
    "🫡": ["salute", "yes sir", "respect", "aye"], "🎅": ["christmas", "xmas"], "🎄": ["xmas", "holiday"],
    "☃️": ["winter", "snow", "cold"], "💅": ["nails", "sassy", "slay", "manicure"], "🤪": ["crazy", "goofy", "silly", "wild"],
    "🗿": ["stone face", "statue", "deadpan", "easter island"], "🆒": ["cool"], "💘": ["cupid", "love", "crush"],
    "🙉": ["monkey", "ears", "not listening"], "🦄": ["magic", "fantasy"], "😘": ["kiss", "love", "xoxo"],
    "💊": ["medicine", "drug", "capsule"], "🙊": ["monkey", "oops", "secret", "quiet"],
    "😎": ["cool", "sunglasses", "chill"], "👾": ["space invader", "game", "retro"],
    "🤷": ["shrug", "dunno", "whatever", "idk"], "😡": ["angry", "mad", "furious", "red"],
    # The wider set.
    "😀": ["happy", "smile"], "😃": ["happy", "smile"], "😄": ["happy", "smile", "laugh"], "😆": ["laugh", "xd", "haha"],
    "😅": ["phew", "sweat", "nervous laugh"], "🙂": ["smile", "fine"], "🙃": ["silly", "sarcasm", "ironic"],
    "😉": ["wink", "flirt"], "😊": ["happy", "blush", "smile"], "😋": ["yum", "delicious", "tasty"],
    "😛": ["tongue", "playful"], "😜": ["tongue", "wink", "crazy"], "😝": ["tongue", "xp"], "🤑": ["money", "rich"],
    "🤭": ["giggle", "oops", "shy"], "🤫": ["shh", "quiet", "secret"], "🤥": ["lie", "pinocchio"],
    "😶": ["silent", "speechless", "blank"], "😏": ["smirk", "smug", "flirt"], "😒": ["unamused", "meh", "annoyed"],
    "🙄": ["eye roll", "whatever", "ugh"], "😬": ["grimace", "awkward", "yikes"], "😌": ["relieved", "calm", "content"],
    "😔": ["pensive", "sad", "disappointed"], "😪": ["sleepy", "tired"], "🤤": ["drool", "hungry", "want"],
    "😷": ["mask", "sick", "ill"], "🤒": ["sick", "fever", "ill"], "🤕": ["hurt", "injured", "bandage"],
    "🤢": ["nauseated", "sick", "gross", "ew"], "🤧": ["sneeze", "sick", "cold"], "🥵": ["hot", "sweating", "heat"],
    "🥶": ["cold", "freezing", "frozen"], "🥳": ["party", "celebrate", "birthday"], "🥸": ["disguise", "incognito"],
    "🧐": ["monocle", "inspect", "curious"], "😕": ["confused", "unsure"], "😟": ["worried", "concerned"],
    "🙁": ["frown", "sad"], "☹️": ["frown", "sad"], "😲": ["astonished", "shocked", "gasp"],
    "😳": ["flushed", "embarrassed", "blush", "shocked"], "🥺": ["pleading", "puppy eyes", "please", "cute"],
    "🥹": ["holding back tears", "touched", "grateful", "proud"], "😦": ["frowning", "shocked"],
    "😧": ["anguished", "shocked"], "😰": ["anxious", "sweat", "nervous"], "😥": ["sad", "relieved", "disappointed"],
    "😓": ["sweat", "tired", "downcast"], "😖": ["confounded", "frustrated"], "😣": ["persevere", "struggling"],
    "😞": ["disappointed", "sad"], "😩": ["weary", "tired", "ugh"], "😫": ["tired", "exhausted", "ugh"],
    "😤": ["huff", "triumph", "frustrated", "steam"], "😠": ["angry", "mad"], "🤯": ["mind blown", "wow"],
    "🥲": ["smiling with tear", "bittersweet", "grateful"], "🫠": ["melting", "embarrassed", "hot"],
    "🫢": ["gasp", "oops", "shocked", "cover mouth"], "🫣": ["peeking", "shy", "scared"], "🫤": ["diagonal mouth", "meh", "unsure"],
    "🫥": ["dotted", "invisible", "hidden"], "🥱": ["yawn", "bored"], "😵": ["dizzy", "knocked out", "dead"],
    "😵‍💫": ["dizzy", "spiral", "confused"], "🤐": ["zipper", "quiet", "sealed lips"], "🤠": ["cowboy", "yeehaw"],
    "🤖": ["robot", "bot"], "👽": ["alien", "ufo"], "💀": ["skull", "dead", "im dead", "lol"], "☠️": ["skull", "danger", "dead"],
    "😺": ["cat", "happy"], "😸": ["cat", "grin"], "😹": ["cat", "laugh", "lol"], "😻": ["cat", "love", "heart eyes"],
    "😼": ["cat", "smirk"], "😽": ["cat", "kiss"], "🙀": ["cat", "scream", "shocked"], "😿": ["cat", "cry", "sad"],
    "😾": ["cat", "angry"], "👋": ["wave", "hello", "hi", "bye"], "🤚": ["stop", "hand"], "🖐️": ["hand", "five"],
    "✋": ["stop", "high five", "hand"], "🖖": ["vulcan", "spock", "live long"], "🫱": ["hand", "right"], "🫲": ["hand", "left"],
    "🫳": ["hand", "drop"], "🫴": ["hand", "offer"], "🫷": ["hand", "push"], "🫸": ["hand", "push"],
    "✌️": ["peace", "victory", "two"], "🤞": ["fingers crossed", "luck", "hope"], "🫰": ["heart", "money", "snap"],
    "🤟": ["love you", "rock"], "🤘": ["rock", "metal", "horns"], "🤙": ["call me", "shaka", "hang loose"],
    "👈": ["left", "point"], "👉": ["right", "point"], "👆": ["up", "point"], "👇": ["down", "point"], "☝️": ["one", "point", "up"],
    "🫵": ["you", "point"], "✊": ["fist", "power", "solidarity"], "👊": ["fist bump", "punch", "bro"],
    "🤛": ["fist bump", "left"], "🤜": ["fist bump", "right"], "🙌": ["raised hands", "hooray", "praise", "yay"],
    "🫶": ["heart hands", "love"], "👐": ["open hands", "hug"], "🤲": ["palms up", "please", "offer"],
    "💪": ["muscle", "strong", "flex", "strength"], "🦾": ["robot arm", "strong"], "🧠": ["brain", "smart", "big brain"],
    "🫀": ["heart", "organ"], "🫁": ["lungs", "breathe"], "🦷": ["tooth", "dentist"], "🦴": ["bone"],
    "👶": ["baby"], "🧑": ["person"], "👩": ["woman"], "👨": ["man"], "🧓": ["old", "elder"], "👮": ["police", "cop"],
    "🕵️": ["detective", "spy", "sleuth"], "🧑‍💻": ["coder", "developer", "programmer"], "🧑‍🚀": ["astronaut", "space"],
    "🦸": ["superhero", "hero"], "🦹": ["villain"], "🧙": ["wizard", "mage", "magic"], "🧛": ["vampire"], "🧟": ["zombie"],
    "🧞": ["genie", "wish"], "🧜": ["mermaid", "merperson"], "🧚": ["fairy"], "🎩": ["top hat", "fancy", "magic"],
    "👑": ["crown", "king", "queen", "royal"], "💍": ["ring", "engaged", "married"], "💎": ["gem", "diamond", "precious"],
    "🐶": ["dog", "puppy"], "🐱": ["cat", "kitten"], "🐭": ["mouse"], "🐹": ["hamster"], "🐰": ["rabbit", "bunny"],
    "🦊": ["fox"], "🐻": ["bear"], "🐼": ["panda"], "🐨": ["koala"], "🐯": ["tiger"], "🦁": ["lion"], "🐮": ["cow"],
    "🐷": ["pig"], "🐸": ["frog"], "🐵": ["monkey"], "🐔": ["chicken"], "🐧": ["penguin"], "🐦": ["bird"], "🐤": ["chick", "baby bird"],
    "🦆": ["duck"], "🦅": ["eagle"], "🦉": ["owl"], "🦇": ["bat"], "🐺": ["wolf"], "🐗": ["boar"], "🐴": ["horse"],
    "🦋": ["butterfly"], "🐌": ["snail", "slow"], "🐛": ["bug", "caterpillar"], "🐝": ["bee", "honey"], "🐞": ["ladybug"],
    "🦂": ["scorpion"], "🐢": ["turtle", "slow"], "🐍": ["snake"], "🦎": ["lizard"], "🦖": ["t-rex", "dinosaur"],
    "🦕": ["dinosaur", "sauropod"], "🐙": ["octopus"], "🦑": ["squid"], "🦐": ["shrimp"], "🦀": ["crab"], "🐡": ["blowfish"],
    "🐠": ["fish", "tropical"], "🐟": ["fish"], "🐬": ["dolphin"], "🦈": ["shark"], "🐊": ["crocodile"], "🐘": ["elephant"],
    "🦒": ["giraffe"], "🦘": ["kangaroo"], "🦥": ["sloth", "slow", "lazy"], "🦦": ["otter"], "🦔": ["hedgehog"], "🐿️": ["chipmunk", "squirrel"],
    "🦩": ["flamingo"], "🦚": ["peacock"], "🦜": ["parrot"], "🐉": ["dragon"], "🐲": ["dragon"], "🌵": ["cactus"],
    "🎄": ["xmas", "holiday"], "🌲": ["tree", "evergreen"], "🌴": ["palm", "beach", "tropical"], "🍀": ["clover", "luck", "lucky"],
    "🌹": ["rose", "flower", "love"], "🌸": ["cherry blossom", "flower", "spring"], "🌼": ["blossom", "flower"], "🌻": ["sunflower", "flower"],
    "🌷": ["tulip", "flower"], "💐": ["bouquet", "flowers"], "🍄": ["mushroom"], "🌍": ["earth", "world", "globe"],
    "🌙": ["moon", "night"], "☀️": ["sun", "sunny"], "⭐": ["star"], "🌟": ["star", "sparkle", "glowing"], "✨": ["sparkles", "magic", "shiny"],
    "💫": ["dizzy", "star"], "🌈": ["rainbow", "pride"], "☁️": ["cloud"], "⛈️": ["storm", "thunder"], "❄️": ["snowflake", "cold", "winter"],
    "🌊": ["wave", "ocean", "sea"], "💧": ["drop", "water", "sweat"], "🍎": ["apple", "fruit"], "🍋": ["lemon", "sour"], "🍉": ["watermelon"],
    "🍇": ["grapes"], "🍒": ["cherries", "cherry"], "🍑": ["peach", "butt"], "🥑": ["avocado"], "🍕": ["pizza"],
    "🍔": ["burger", "hamburger"], "🍟": ["fries"], "🌮": ["taco"], "🍣": ["sushi"], "🍜": ["ramen", "noodles"], "🍿": ["popcorn", "movie"],
    "🍩": ["donut", "doughnut"], "🍪": ["cookie"], "🎂": ["birthday", "cake"], "🍰": ["cake", "shortcake"], "🧁": ["cupcake"],
    "🍫": ["chocolate"], "🍭": ["lollipop", "candy"], "🍺": ["beer"], "🍻": ["cheers", "beers"], "🥂": ["cheers", "toast", "champagne"],
    "🍷": ["wine"], "🍸": ["cocktail", "martini"], "☕": ["coffee", "hot drink"], "🍵": ["tea"], "🧋": ["bubble tea", "boba"],
    "🎈": ["balloon", "party", "birthday"], "🎁": ["gift", "present"], "🎊": ["confetti", "party", "celebrate"], "🎀": ["ribbon", "bow"],
    "🥇": ["first", "gold medal", "winner"], "🥈": ["second", "silver medal"], "🥉": ["third", "bronze medal"], "🏅": ["medal", "winner"],
    "🎯": ["bullseye", "target", "direct hit"], "🎮": ["game", "gaming", "controller"], "🎲": ["dice", "game"], "🎸": ["guitar", "rock"],
    "🎵": ["music", "note"], "🎶": ["music", "notes"], "🎤": ["microphone", "sing", "karaoke"], "🎧": ["headphones", "music"],
    "📸": ["camera", "photo"], "🎬": ["clapper", "movie", "action"], "🚀": ["rocket", "launch", "to the moon"], "✈️": ["airplane", "travel", "flight"],
    "🚗": ["car"], "🏠": ["house", "home"], "💡": ["idea", "light bulb"], "🔑": ["key"], "🔒": ["lock", "locked", "secure"],
    "🔓": ["unlock", "unlocked"], "🛡️": ["shield", "protect", "secure"], "⚔️": ["swords", "fight"], "🧲": ["magnet"], "🔮": ["crystal ball", "magic", "future"],
    "📌": ["pin"], "📎": ["paperclip"], "✅": ["check", "done", "yes", "correct"], "❌": ["cross", "no", "wrong", "x"],
    "❓": ["question", "what"], "❗": ["exclamation", "important"], "‼️": ["double exclamation", "important"], "⁉️": ["what", "interrobang"],
    "⚠️": ["warning", "caution"], "🚫": ["prohibited", "no", "forbidden"], "♻️": ["recycle"], "💤": ["zzz", "sleep", "bored"],
    "💢": ["anger", "angry"], "💥": ["boom", "explosion", "collision"], "💦": ["sweat", "splash", "water"], "💨": ["dash", "fast", "wind"],
    "🕳️": ["hole"], "💬": ["speech", "comment", "chat"], "💭": ["thought", "thinking"], "🗯️": ["anger bubble", "shout"],
    "💤": ["zzz", "sleep"], "🧡": ["orange heart", "heart", "love"], "💛": ["yellow heart", "heart", "love"], "💚": ["green heart", "heart", "love"],
    "💙": ["blue heart", "heart", "love"], "💜": ["purple heart", "heart", "love"], "🖤": ["black heart", "heart", "dark"],
    "🤍": ["white heart", "heart", "pure"], "🤎": ["brown heart", "heart"], "🩷": ["pink heart", "heart", "love"],
    "🩵": ["light blue heart", "heart"], "🩶": ["grey heart", "heart"], "💕": ["two hearts", "love"], "💞": ["revolving hearts", "love"],
    "💓": ["beating heart", "love"], "💗": ["growing heart", "love"], "💖": ["sparkling heart", "love"], "💝": ["heart with ribbon", "gift", "love"],
    "💟": ["heart decoration", "love"], "❣️": ["heart exclamation", "love"], "❤️‍🩹": ["mending heart", "healing", "recover"],
    "💌": ["love letter", "mail"], "🫂": ["hug", "people hugging", "comfort"], "👏": ["applause", "clap"], "🙏": ["thanks", "please", "pray"],
}

# The wider set, in display order after Telegram's list.
WIDER = (
    "😀 😃 😄 😆 😅 🙂 🙃 😉 😊 😋 😛 😜 😝 🤑 🤭 🤫 🤥 😶 😏 😒 🙄 😬 😌 😔 😪 🤤 😷 🤒 🤕 🤢 🤧 🥵 🥶 🥳 🥸 🧐 "
    "😕 😟 🙁 ☹️ 😲 😳 🥺 🥹 😦 😧 😰 😥 😓 😖 😣 😞 😩 😫 😤 😠 🥲 🫠 🫢 🫣 🫤 🫥 😵 😵‍💫 🤐 🤠 🤖 👽 💀 ☠️ "
    "😺 😸 😹 😻 😼 😽 🙀 😿 😾 "
    "👋 🤚 🖐️ ✋ 🖖 ✌️ 🤞 🫰 🤟 🤘 🤙 👈 👉 👆 👇 ☝️ 🫵 ✊ 👊 🤛 🤜 🙌 🫶 👐 🤲 💪 🧠 🫂 "
    "🧡 💛 💚 💙 💜 🖤 🤍 🤎 🩷 🩵 🩶 💕 💞 💓 💗 💖 💝 💟 ❣️ ❤️‍🩹 💌 "
    "👑 💍 💎 🎩 🕵️ 🧑‍💻 🧑‍🚀 🦸 🦹 🧙 🧛 🧟 🧞 🧜 🧚 "
    "🐶 🐱 🐭 🐹 🐰 🦊 🐻 🐼 🐨 🐯 🦁 🐮 🐷 🐸 🐵 🐔 🐧 🐦 🐤 🦆 🦅 🦉 🦇 🐺 🐗 🐴 🦋 🐌 🐛 🐝 🐞 🦂 🐢 🐍 🦎 🦖 🦕 "
    "🐙 🦑 🦐 🦀 🐡 🐠 🐟 🐬 🦈 🐊 🐘 🦒 🦘 🦥 🦦 🦔 🐿️ 🦩 🦚 🦜 🐉 "
    "🌵 🌲 🌴 🍀 🌹 🌸 🌼 🌻 🌷 💐 🍄 🌍 🌙 ☀️ ⭐ 🌟 ✨ 💫 🌈 ☁️ ⛈️ ❄️ 🌊 💧 "
    "🍎 🍋 🍉 🍇 🍒 🍑 🥑 🍕 🍔 🍟 🌮 🍣 🍜 🍿 🍩 🍪 🎂 🍰 🧁 🍫 🍭 🍺 🍻 🥂 🍷 🍸 ☕ 🍵 🧋 "
    "🎈 🎁 🎊 🎀 🥇 🥈 🥉 🏅 🎯 🎮 🎲 🎸 🎵 🎶 🎤 🎧 📸 🎬 🚀 ✈️ 🚗 🏠 💡 🔑 🔒 🔓 🛡️ ⚔️ 🧲 🔮 📌 📎 "
    "✅ ❌ ❓ ❗ ‼️ ⁉️ ⚠️ 🚫 ♻️ 💤 💢 💥 💦 💨 💬 💭 🗯️"
).split()

# Unicode's formal names where they are not what anyone calls the emoji.
NAME_OVERRIDES = {
    "❤️": "red heart", "☹️": "frowning face", "✅": "check mark button", "❌": "cross mark",
    "😵‍💫": "face with spiral eyes", "🧑‍💻": "technologist", "👨‍💻": "man technologist",
    "❤️‍🩹": "mending heart", "❤️‍🔥": "heart on fire", "🕊️": "dove", "☃️": "snowman",
    "✍️": "writing hand", "🖐️": "hand with fingers splayed", "☝️": "index pointing up",
    "✌️": "victory hand", "☀️": "sun", "☁️": "cloud", "⛈️": "cloud with lightning and rain",
    "❄️": "snowflake", "✈️": "airplane", "⚔️": "crossed swords", "⚠️": "warning", "♻️": "recycling",
    "‼️": "double exclamation mark", "⁉️": "exclamation question mark", "❣️": "heart exclamation",
    "🗯️": "right anger bubble", "🕳️": "hole", "🐿️": "chipmunk", "🕵️": "detective", "🛡️": "shield",
    "🧑‍🚀": "astronaut", "☠️": "skull and crossbones", "💯": "hundred points", "🆒": "cool button",
    "⚡": "high voltage", "⭐": "star", "☕": "hot beverage", "🍾": "bottle with popping cork",
    "🌚": "new moon face", "🎅": "santa claus", "🎄": "christmas tree", "🎃": "jack-o-lantern",
    "🗿": "moai", "🤩": "star-struck", "😮": "face with open mouth", "🙏": "folded hands",
}

def name_of(emoji: str) -> str:
    if emoji in NAME_OVERRIDES:
        return NAME_OVERRIDES[emoji]
    base = emoji.replace("️", "")
    if "‍" in base:
        parts = [unicodedata.name(c, "").lower() for c in base.split("‍")]
        return " ".join(p for p in parts if p)
    if len(base) == 1:
        return unicodedata.name(base, "").lower()
    return " ".join(unicodedata.name(c, "").lower() for c in base)

def keywords(emoji: str) -> list[str]:
    name = name_of(emoji)
    # Unicode names are shouty and literal; trim the bits nobody types.
    name = re.sub(r"\b(sign|symbol)\b", "", name).replace("-", " ").replace("  ", " ").strip()
    words = [name] if name else []
    for extra in SYNONYMS.get(emoji, []):
        if extra not in words:
            words.append(extra)
    return words

seen = set()
ordered = []
for e in QUICK + TELEGRAM + WIDER:
    if e in seen:
        continue
    seen.add(e)
    ordered.append(e)

table = {e: keywords(e) for e in ordered}
missing = [e for e, k in table.items() if not k]
assert not missing, missing

# --- TypeScript -------------------------------------------------------------
def ts_list(items, indent):
    lines, line = [], ""
    for it in items:
        piece = json.dumps(it, ensure_ascii=False) + ", "
        if len(line) + len(piece) > 96:
            lines.append(line.rstrip())
            line = ""
        line += piece
    if line:
        lines.append(line.rstrip())
    return "\n".join(indent + l for l in lines)

ts = []
ts.append("/* Generated by scripts/gen_reaction_set.py — the reaction set and the words each emoji answers to.")
ts.append(" * The quick seven first, then Telegram's standard set, then a wider curated set named from Unicode")
ts.append(" * with hand-written synonyms. Keep in step with ios/shroud/Services/Messaging/ReactionSet.swift. */")
ts.append("")
ts.append("/** The bar's quick row: Telegram's seven. */")
ts.append("export const QUICK_REACTIONS = " + json.dumps(QUICK, ensure_ascii=False) + ";")
ts.append("")
ts.append("/** Everything the expanded picker offers, quick seven first so they keep their places. */")
ts.append("export const ALL_REACTIONS: string[] = [")
ts.append(ts_list(ordered, "  "))
ts.append("];")
ts.append("")
ts.append("/** The words each emoji answers to: its Unicode name first, then what people type for it. */")
ts.append("export const REACTION_KEYWORDS: Record<string, string[]> = {")
for e in ordered:
    ts.append(f"  {json.dumps(e, ensure_ascii=False)}: {json.dumps(table[e], ensure_ascii=False)},")
ts.append("};")
(ROOT / "web/src/reactionSet.ts").write_text("\n".join(ts) + "\n")

# --- Swift ------------------------------------------------------------------
def swift_str(s):
    return '"' + s.replace("\\", "\\\\").replace('"', '\\"') + '"'

sw = []
sw.append("// Generated by scripts/gen_reaction_set.py — the reaction set and the words each emoji answers to.")
sw.append("// The quick seven first, then Telegram's standard set, then a wider curated set named from Unicode")
sw.append("// with hand-written synonyms. Keep in step with web/src/reactionSet.ts.")
sw.append("")
sw.append("nonisolated enum ReactionSet {")
sw.append("    /// The bar's quick row: Telegram's seven.")
sw.append("    static let quick: [String] = [" + ", ".join(swift_str(e) for e in QUICK) + "]")
sw.append("")
sw.append("    /// Everything the expanded picker offers, quick seven first so they keep their places.")
sw.append("    static let all: [String] = [")
lines, line = [], ""
for e in ordered:
    piece = swift_str(e) + ", "
    if len(line) + len(piece) > 96:
        lines.append(line.rstrip()); line = ""
    line += piece
if line:
    lines.append(line.rstrip())
sw.extend("        " + l for l in lines)
sw.append("    ]")
sw.append("")
sw.append("    /// The words each emoji answers to: its Unicode name first, then what people type for it.")
sw.append("    static let keywords: [String: [String]] = [")
for e in ordered:
    sw.append(f"        {swift_str(e)}: [" + ", ".join(swift_str(k) for k in table[e]) + "],")
sw.append("    ]")
sw.append("}")
(ROOT / "ios/shroud/Services/Messaging/ReactionSet.swift").write_text("\n".join(sw) + "\n")

print(len(ordered), "emoji;", sum(len(v) for v in table.values()), "keywords")
for e in ["😀", "🐱", "✅", "🖐️", "😵‍💫", "🧑‍💻", "❤️‍🩹", "☹️"]:
    print(e, table[e])
