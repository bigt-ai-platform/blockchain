import { pbkdf2 } from '@noble/hashes/pbkdf2';
import { sha256 } from '@noble/hashes/sha256';
import { MnemonicException } from './MnemonicException';
import { Utils } from '../utils/Utils';

/**
 * A MnemonicCode object may be used to convert between binary seed values and
 * lists of words per <a href="https://github.com/bitcoin/bips/blob/master/bip-0039.mediawiki">the BIP 39
 * specification</a>
 */
export class MnemonicCode {
    private wordList: string[];

    private static readonly BIP39_ENGLISH_RESOURCE_NAME = "mnemonic/wordlist/english.txt";
    private static readonly BIP39_ENGLISH_SHA256 = "ad90bf3beb7b0eb7e5acd74727dc0da96e0a280a258354e7293fb7e211ac03db";

    /** UNIX time for when the BIP39 standard was finalised. This can be used as a default seed birthday. */
    public static BIP39_STANDARDISATION_TIME_SECS = 1381276800;

    private static readonly PBKDF2_ROUNDS = 2048;

    public static INSTANCE: MnemonicCode;

    // In a browser environment, you might need to fetch the wordlist asynchronously.
    // For Node.js, you can read it from the file system.
    // For simplicity, I'll hardcode the English wordlist here for now.
    private static ENGLISH_WORDLIST: string[] = [
        "abandon", "ability", "able", "about", "above", "absent", "absorb", "abstract", "absurd", "abuse",
        "access", "accident", "account", "accuse", "achieve", "acid", "acoustic", "acquire", "across", "act",
        "action", "active", "activity", "actor", "actual", "adapt", "add", "addict", "address", "adjust",
        "admit", "adult", "advance", "advice", "aerobic", "affair", "affect", "afford", "afraid", "again",
        "age", "agent", "agree", "ahead", "aim", "air", "airport", "aisle", "alarm", "album",
        "alcohol", "alert", "alien", "all", "alley", "allow", "almost", "alone", "already", "also",
        "alter", "always", "amateur", "amazing", "among", "amount", "amuse", "analyst", "anchor", "ancient",
        "anger", "angle", "angry", "animal", "announce", "annual", "another", "answer", "antenna", "antique",
        "anxiety", "any", "apart", "apology", "appear", "apple", "approve", "april", "arch", "arctic",
        "area", "arena", "argue", "arm", "armed", "armor", "army", "around", "arrange", "arrest",
        "arrive", "arrow", "art", "artefact", "artist", "artwork", "ask", "aspect", "assault", "asset",
        "assist", "assume", "asthma", "athlete", "atom", "attack", "attend", "attitude", "attract", "auction",
        "audit", "august", "aunt", "author", "auto", "autumn", "average", "avocado", "avoid", "awake",
        "aware", "away", "awesome", "awful", "awkward", "axis", "baby", "bachelor", "bacon", "badge",
        "bag", "balance", "balcony", "ball", "bamboo", "banana", "banner", "bar", "barely", "bargain",
        "barrel", "base", "basic", "basket", "battle", "beach", "deal", "bean", "bear", "beauty",
        "because", "become", "beef", "before", "begin", "behave", "behind", "believe", "below", "belt",
        "bench", "benefit", "best", "betray", "better", "between", "beyond", "bicycle", "bid", "bike",
        "bind", "biology", "bird", "birth", "bitter", "black", "blade", "blame", "blanket", "blast",
        "bleak", "bless", "blind", "blood", "blossom", "blouse", "blue", "blur", "board", "boat",
        "body", "boil", "bomb", "bone", "bonus", "book", "boost", "border", "boring", "borrow",
        "boss", "bottom", "bounce", "box", "boy", "brain", "brand", "brass", "brave", "bread",
        "break", "breeze", "brick", "bridge", "brief", "bright", "bring", "brisk", "broccoli", "broken",
        "bronze", "broom", "brother", "brown", "brush", "bubble", "buddy", "budget", "buffalo", "build",
        "bulb", "bulk", "bullet", "bundle", "bunker", "burden", "burger", "burst", "bus", "business",
        "busy", "butter", "buyer", "buzz", "cabbage", "cabin", "cable", "cactus", "cage", "cake",
        "call", "calm", "camera", "camp", "can", "canal", "cancel", "candy", "cannon", "canoe",
        "canvas", "canyon", "capable", "capital", "captain", "car", "carbon", "card", "care", "career",
        "carry", "cart", "case", "cash", "casino", "castle", "casual", "cat", "catalog", "catch",
        "category", "cattle", "caught", "cause", "caution", "cave", "ceiling", "celery", "cement", "census",
        "century", "cereal", "certain", "chair", "chalk", "champion", "change", "channel", "chapter", "charge",
        "chase", "chat", "cheap", "check", "cheese", "chef", "cherry", "chest", "chicken", "chief",
        "child", "chimney", "choice", "choose", "chrysanthemum", "chuckle", "church", "cinema", "circle", "citizen",
        "city", "civil", "claim", "clash", "class", "clean", "clear", "clever", "click", "client",
        "cliff", "climb", "clinic", "clip", "clock", "clog", "close", "cloth", "cloud", "clown",
        "club", "clump", "cluster", "clutch", "coach", "coast", "code", "coffee", "coil", "coin",
        "collect", "color", "column", "combine", "come", "comfort", "comic", "common", "company", "compare",
        "compete", "complete", "complex", "comply", "compress", "computer", "concentrate", "concept", "concern", "conduct",
        "confess", "confirm", "confuse", "connect", "consider", "control", "convince", "cook", "cool", "cope",
        "copy", "coral", "core", "corn", "correct", "cost", "cotton", "couch", "country", "couple",
        "course", "cousin", "cover", "coyote", "crack", "cradle", "craft", "cram", "crane", "crash",
        "crater", "crawl", "crazy", "cream", "credit", "creek", "crew", "cricket", "crime", "crisp",
        "critic", "crop", "cross", "crouch", "crowd", "crucial", "cruel", "cruise", "crumble", "crunch",
        "crush", "cry", "crystal", "cube", "culture", "cup", "curious", "current", "curtain", "curve",
        "cushion", "custom", "cute", "cycle", "dad", "damage", "dance", "danger", "daring", "dash",
        "daughter", "dawn", "day", "deal", "debate", "decade", "december", "decide", "decline", "decorate",
        "decrease", "deer", "defense", "define", "defy", "degree", "delay", "deliver", "demand", "demise",
        "denial", "dentist", "deny", "depart", "depend", "deposit", "depth", "deputy", "derive", "describe",
        "desert", "design", "desk", "despair", "destroy", "detail", "detect", "develop", "device", "devote",
        "diagram", "dial", "diamond", "diary", "die", "diesel", "diet", "differ", "digital", "dignity",
        "dilemma", "dinner", "dinosaur", "direct", "dirt", "disagree", "discover", "disease", "dish", "dismiss",
        "disorder", "display", "distance", "divert", "divide", "divorce", "dizzy", "doctor", "document", "dog",
        "doll", "dolphin", "domain", "donate", "donkey", "donor", "door", "dose", "double", "dove",
        "draft", "dragon", "drama", "drastic", "draw", "dream", "dress", "drift", "drill", "drink",
        "drip", "drive", "drop", "drum", "dry", "duck", "dumb", "dune", "during", "dust",
        "dutch", "duty", "dwarf", "dynamic", "eager", "eagle", "early", "earn", "earth", "easily",
        "east", "easy", "echo", "ecology", "economy", "edge", "edit", "educate", "effort", "egg",
        "eight", "either", "elbow", "elder", "electric", "elegant", "element", "elephant", "elevate", "elf",
        "elite", "else", "embark", "embody", "embrace", "emerge", "emotion", "employ", "empty", "enable",
        "enact", "end", "endless", "endorse", "enemy", "energy", "enforce", "engage", "engine", "enhance",
        "enjoy", "enlist", "enough", "enrich", "enroll", "ensure", "enter", "entire", "entry", "envelope",
        "episode", "equal", "equip", "era", "erase", "erode", "erosion", "error", "erupt", "escape",
        "essay", "essence", "estate", "eternal", "ethics", "evidence", "evil", "evoke", "evolve", "exact",
        "example", "excess", "exchange", "excite", "exclude", "excuse", "execute", "exercise", "exhaust", "exhibit",
        "exile", "exist", "exit", "exotic", "expand", "expect", "expire", "explain", "expose", "express",
        "extend", "extra", "eye", "eyebrow", "face", "facepalm", "factor", "fade", "fail", "fair",
        "fall", "family", "famous", "fan", "fancy", "fantasy", "farm", "fashion", "fat", "fatal",
        "father", "fatigue", "fault", "favor", "fear", "feature", "february", "federal", "fee", "feed",
        "feel", "female", "fence", "festival", "fetch", "fever", "few", "fiber", "fiction", "field",
        "figure", "file", "film", "filter", "final", "find", "fine", "finger", "finish", "fire",
        "firm", "first", "fiscal", "fish", "fit", "fitness", "fix", "flag", "flame", "flash",
        "flat", "flavor", "flee", "flight", "flip", "float", "flock", "floor", "flower", "fluid",
        "flush", "fly", "foam", "focus", "fog", "foil", "fold", "follow", "food", "foot",
        "force", "forest", "forget", "fork", "form", "forum", "forward", "fossil", "foster", "found",
        "fox", "fragile", "frame", "frequent", "fresh", "friend", "fringe", "frog", "front", "frost",
        "frown", "frozen", "fruit", "fuel", "fun", "funny", "furnace", "fury", "future", "gadget",
        "gain", "galaxy", "gallery", "game", "gap", "garage", "garden", "garlic", "garment", "gas",
        "gasp", "gate", "gather", "gauge", "gaze", "general", "genius", "genre", "gentle", "genuine",
        "gesture", "ghost", "giant", "gift", "giggle", "ginger", "giraffe", "girl", "give", "glad",
        "glance", "glass", "glen", "glide", "global", "gloom", "glory", "glove", "glow", "glue",
        "go", "goal", "goose", "gorgeous", "gorilla", "gospel", "gossip", "govern", "gown", "grab",
        "grace", "grain", "grant", "grape", "grass", "gravity", "great", "green", "grid", "grief",
        "grit", "ground", "group", "grow", "growth", "guard", "guess", "guide", "guilt", "guitar",
        "gun", "gym", "habit", "hair", "half", "hammer", "sample", "hand", "happy", "hard",
        "harvest", "haste", "hat", "hatch", "have", "hawk", "hazard", "head", "health", "heart",
        "heavy", "height", "hello", "help", "hence", "her", "here", "hero", "hidden", "high",
        "hill", "hint", "hip", "hire", "history", "hobby", "hockey", "hold", "hole", "holiday",
        "hollow", "home", "honey", "hood", "hope", "horn", "horror", "horse", "hospital", "host",
        "hotel", "hour", "hover", "how", "html", "huge", "human", "humble", "humor", "hundred",
        "hungry", "hunt", "hurry", "hurt", "husband", "hybrid", "ice", "icon", "idea", "identify",
        "idle", "ignore", "ill", "illegal", "illness", "image", "imitate", "immense", "immune", "impact",
        "impose", "improve", "impulse", "in", "inch", "include", "income", "increase", "index", "indicate",
        "indoor", "industry", "infant", "inform", "initial", "inject", "injury", "inmate", "inner", "innocent",
        "input", "inquiry", "insane", "insect", "inside", "inspire", "install", "intact", "interest", "interpret",
        "into", "invert", "invest", "invite", "involve", "iron", "is", "island", "isolate", "issue",
        "item", "ivory", "jacket", "jaguar", "jar", "jazz", "jealous", "jeans", "jelly", "jewel",
        "job", "join", "joke", "journey", "joy", "judge", "juice", "july", "july", "jump",
        "jungle", "junior", "junk", "just", "kangaroo", "keen", "keep", "ketchup", "key", "kick",
        "kid", "kidney", "kind", "kingdom", "kiss", "kit", "kitchen", "kite", "kitten", "kiwi",
        "knee", "knife", "knock", "know", "knowledge", "koala", "kongo", "label", "labor", "ladder",
        "lady", "lake", "lamp", "language", "laptop", "large", "last", "late", "laugh", "laundry",
        "lava", "law", "lawn", "lead", "lazy", "learn", "leaf", "league", "lean", "leave",
        "lecture", "left", "leg", "legal", "legend", "leisure", "lemon", "lend", "length", "lens",
        "leopard", "lesson", "letter", "level", "liar", "liberty", "library", "license", "life", "lift",
        "light", "like", "limb", "limit", "link", "lion", "liquid", "list", "little", "live",
        "lizard", "load", "loan", "lobster", "local", "lock", "logic", "lonely", "long", "loop",
        "lottery", "loud", "lounge", "love", "loyal", "lucky", "luggage", "lumber", "lunar", "lunch",
        "luxury", "lyrics", "machine", "mad", "magic", "magnet", "maid", "mail", "main", "major",
        "make", "mammal", "man", "manage", "mandate", "mango", "mansion", "manual", "maple", "marble",
        "march", "margin", "marine", "market", "marriage", "mask", "mass", "master", "match", "material",
        "math", "matrix", "matter", "maximum", "maze", "meadow", "mean", "measure", "meat", "mechanic",
        "medal", "media", "melody", "melt", "member", "memory", "mention", "menu", "mercy", "merge",
        "message", "metal", "meter", "method", "mexico", "middle", "midnight", "milk", "million", "mimic",
        "mind", "minimum", "minor", "mint", "minute", "miracle", "mirror", "misery", "miss", "mistake",
        "mix", "mixed", "mixture", "mobile", "model", "modern", "modify", "mom", "moment", "monitor",
        "monkey", "monster", "month", "moon", "moral", "more", "morning", "mosquito", "mother", "motion",
        "motor", "mountain", "mouse", "move", "movie", "much", "muffin", "mule", "multiply", "muscle",
        "museum", "mushroom", "music", "must", "mutual", "my", "mystery", "myth", "naive", "name",
        "napkin", "narrow", "nasty", "nation", "nature", "near", "neck", "need", "negative", "neglect",
        "neither", "nephew", "nerve", "nest", "net", "network", "neutral", "never", "news", "next",
        "nice", "night", "noble", "noise", "nomad", "north", "nose", "notable", "note", "nothing",
        "notice", "novel", "now", "nuclear", "number", "nurse", "nut", "oak", "obey", "object",
        "oblige", "obscure", "observe", "obtain", "occasion", "occupy", "occur", "ocean", "october", "odor",
        "off", "offer", "office", "often", "oil", "okay", "old", "olive", "olympic", "omit",
        "once", "one", "onion", "only", "open", "opera", "opinion", "oppose", "option", "orange",
        "orbit", "orchard", "order", "ordinary", "organ", "orient", "original", "orphan", "ostrich", "other",
        "outdoor", "outer", "output", "outside", "oval", "oven", "over", "own", "owner", "oxygen",
        "oyster", "ozone", "pact", "paddle", "page", "pair", "palace", "palm", "panda", "panel",
        "panic", "panther", "paper", "parade", "parent", "park", "parrot", "party", "pass", "patch",
        "path", "patient", "patrol", "pattern", "pause", "pave", "payment", "peace", "peanut", "pear",
        "peasant", "pelican", "pen", "penalty", "pencil", "people", "pepper", "perfect", "permit", "person",
        "pet", "phone", "photo", "phrase", "physical", "piano", "picnic", "picture", "piece", "pig",
        "pigeon", "pill", "pilot", "pink", "pioneer", "pipe", "pistol", "pitch", "pizza", "place",
        "plain", "planet", "plastic", "plate", "play", "please", "pledge", "plot", "plug", "plus",
        "point", "pole", "police", "policy", "polish", "pollute", "pond", "pony", "pool", "popular",
        "portion", "position", "possess", "post", "potato", "power", "practice", "praise", "predict", "prefer",
        "prepare", "present", "pretty", "prevent", "price", "pride", "primary", "print", "priority", "prison",
        "private", "prize", "problem", "process", "produce", "profit", "program", "project", "promote", "proof",
        "property", "prosper", "protect", "proud", "provide", "public", "pudding", "pull", "pulp", "pulse",
        "pumpkin", "punch", "pupil", "puppy", "purchase", "purity", "purpose", "purse", "push", "put",
        "puzzle", "pyramid", "quality", "quantify", "quantity", "quarter", "question", "quick", "quit", "quiz",
        "quote", "rabbit", "raccoon", "race", "rack", "radar", "radio", "rail", "rain", "raise",
        "rally", "ram", "ranch", "random", "range", "rapid", "rare", "rate", "rather", "raven",
        "raw", "reach", "read", "ready", "real", "reason", "rebuild", "recall", "receive", "recipe",
        "record", "recycle", "reduce", "reflect", "reform", "refuse", "region", "regret", "regular", "reject",
        "relax", "release", "relief", "rely", "remain", "remember", "remind", "remove", "render", "renew",
        "rent", "reopen", "repair", "repeat", "replace", "report", "require", "rescue", "resemble", "resist",
        "resource", "response", "result", "retire", "retreat", "return", "reunion", "reveal", "review", "reward",
        "rhythm", "ribbon", "rice", "rich", "ride", "ridge", "rifle", "right", "rigid", "ring",
        "riot", "ripple", "risk", "ritual", "rival", "river", "road", "roast", "robot", "robust",
        "rocket", "romance", "roof", "rookie", "room", "rose", "rotate", "round", "route", "royal",
        "rubber", "rude", "rug", "rule", "run", "runway", "rural", "rush", "sad", "saddle",
        "sail", "salad", "salmon", "salon", "salt", "salute", "same", "sample", "sand", "satisfy",
        "satoshi", "sauce", "save", "say", "scale", "scan", "scare", "scatter", "scene", "scheme",
        "school", "science", "scissors", "scorpion", "scout", "scrap", "screen", "script", "scrub", "sea",
        "search", "season", "seat", "second", "secret", "section", "security", "seed", "seek", "segment",
        "select", "sell", "seminar", "senior", "sense", "sentence", "series", "service", "session", "set",
        "settle", "setup", "seven", "shadow", "shaft", "shallow", "shame", "shape", "share", "shark",
        "sharp", "shave", "she", "sheep", "sheet", "shelf", "shell", "sheriff", "shield", "shift",
        "shine", "ship", "shiver", "shock", "shoe", "shoot", "shop", "short", "shoulder", "shove",
        "show", "shrimp", "shrink", "sibling", "sick", "side", "siege", "sight", "sign", "silent",
        "silk", "silly", "silver", "similar", "simple", "since", "sing", "siren", "sister", "situate",
        "six", "size", "skate", "sketch", "ski", "skill", "skin", "skirt", "skull", "slab",
        "slam", "sleep", "slice", "slide", "slim", "slip", "slope", "slow", "slush", "small",
        "smart", "smile", "smoke", "smooth", "snack", "snake", "snap", "sniff", "snow", "soap",
        "soccer", "social", "sock", "soft", "soggy", "soil", "solar", "soldier", "solid", "solution",
        "solve", "someone", "song", "soon", "sorry", "sort", "soul", "sound", "soup", "source",
        "south", "space", "spare", "spark", "speak", "speculate", "speed", "spend", "sphere", "spice",
        "spider", "spirit", "split", "spoil", "sponsor", "spoon", "sport", "spot", "spray", "spread",
        "spring", "spy", "square", "squeeze", "squirrel", "stable", "stadium", "staff", "stage", "stairs",
        "stamp", "stand", "start", "state", "stay", "steak", "steel", "stem", "step", "stereo",
        "stick", "still", "sting", "stock", "stomach", "stone", "stool", "story", "strategy", "street",
        "strike", "strong", "struggle", "student", "stuff", "stumble", "style", "subject", "submit", "subway",
        "success", "sudden", "suffer", "sugar", "suggest", "suit", "summer", "sun", "sunny", "sunset",
        "super", "supply", "supreme", "sure", "surface", "surge", "surprise", "surround", "survey", "suspect",
        "sustain", "swallow", "swamp", "swap", "swarm", "swear", "sweet", "swift", "swim", "swing",
        "switch", "sword", "syrup", "system", "table", "tackle", "tag", "tail", "talent", "talk",
        "tank", "tape", "target", "task", "taste", "tattoo", "taxi", "teach", "team", "tend",
        "tennis", "tent", "term", "test", "text", "thank", "that", "the", "then", "theory",
        "there", "they", "thing", "this", "thought", "three", "thrive", "throw", "thumb", "thunder",
        "ticket", "tide", "tiger", "tilt", "timber", "time", "tiny", "tip", "tired", "tissue",
        "title", "toast", "tobacco", "today", "toddler", "toe", "together", "toilet", "token", "tomato",
        "tomorrow", "tone", "tongue", "tonight", "tool", "tooth", "top", "topic", "topple", "torch",
        "tornado", "tortoise", "toss", "total", "tourist", "toward", "tower", "town", "toy", "track",
        "trade", "traffic", "tragedy", "train", "transfer", "trap", "travel", "treat", "tree", "trend",
        "trial", "tribe", "trick", "trigger", "trim", "trip", "trophy", "trouble", "true", "truly",
        "trumpet", "trust", "truth", "try", "tube", "tuition", "tumble", "tuna", "tunnel", "turkey",
        "turn", "turtle", "twelve", "twenty", "twice", "twin", "twist", "two", "type", "tyre",
        "ugly", "umbrella", "unable", "unaware", "uncle", "uncover", "under", "undo", "unfold", "unhappy",
        "uniform", "unite", "universe", "unknown", "unlock", "until", "unusual", "unveil", "update", "upgrade",
        "uphold", "upon", "upper", "upset", "urban", "urge", "usage", "use", "used", "useful",
        "useless", "usual", "utility", "vacation", "valley", "vanish", "vape", "vapor", "various", "vast",
        "vessel", "veteran", "vibrate", "violence", "violet", "virtual", "visa", "visit", "visual", "vital",
        "vivid", "vocal", "voice", "void", "volcano", "volume", "vote", "voyage", "wage", "wagon",
        "wait", "walk", "wall", "walrus", "want", "war", "warm", "warn", "wash", "wasp",
        "waste", "water", "wave", "way", "we", "weak", "wealth", "weapon", "wear", "weasel",
        "weather", "web", "wedding", "weekend", "weird", "welcome", "west", "wet", "whale", "what",
        "wheat", "wheel", "when", "where", "while", "whisper", "wide", "widget", "wild", "will",
        "win", "window", "wine", "wing", "wink", "winner", "winter", "wire", "wisdom", "wise",
        "wish", "witness", "wolf", "woman", "wonder", "wood", "wool", "word", "work", "world",
        "worry", "worth", "wrap", "wreck", "wrestle", "wrist", "write", "wrong", "yard", "year",
        "yellow", "you", "young", "youth", "zebra", "zero", "zone", "zoo"
    ];

    constructor() {
        this.wordList = MnemonicCode.ENGLISH_WORDLIST;
        if (this.wordList.length !== 2048) {
            throw new Error("English wordlist does not contain 2048 words");
        }
        // In a real application, you might want to verify the SHA256 hash of the wordlist here.
    }

    /**
     * Gets the word list this code uses.
     */
    public getWordList(): string[] {
        return this.wordList;
    }

    /**
     * Convert mnemonic word list to seed.
     */
    public static async toSeed(words: string[], passphrase: string): Promise<Uint8Array> {
        const pass = words.join(" ");
        const salt = "mnemonic" + passphrase;

        // PBKDF2 with HMAC-SHA512, 2048 rounds, 64 bytes output
        const seed = await pbkdf2(sha256, new TextEncoder().encode(pass), new TextEncoder().encode(salt), {
            c: MnemonicCode.PBKDF2_ROUNDS,
            dkLen: 64,
            // prf: 'hmac-sha512' is implicit with sha512 and pbkdf2
        });
        return seed;
    }

    /**
     * Convert mnemonic word list to original entropy value.
     */
    public toEntropy(words: string[]): Uint8Array {
        if (words.length % 3 > 0) {
            throw new MnemonicException.MnemonicLengthException("Word list size must be multiple of three words.");
        }

        if (words.length === 0) {
            throw new MnemonicException.MnemonicLengthException("Word list is empty.");
        }

        const concatLenBits = words.length * 11;
        const concatBits = new Array<boolean>(concatLenBits);
        let wordindex = 0;
        for (const word of words) {
            const ndx = this.wordList.indexOf(word);
            if (ndx < 0) {
                throw new MnemonicException.MnemonicWordException(word);
            }

            for (let ii = 0; ii < 11; ++ii) {
                concatBits[(wordindex * 11) + ii] = (ndx & (1 << (10 - ii))) !== 0;
            }
            wordindex++;
        }

        const checksumLengthBits = concatLenBits / 33;
        const entropyLengthBits = concatLenBits - checksumLengthBits;

        const entropy = new Uint8Array(entropyLengthBits / 8);
        for (let ii = 0; ii < entropy.length; ++ii) {
            for (let jj = 0; jj < 8; ++jj) {
                if (concatBits[(ii * 8) + jj]) {
                    entropy[ii] |= 1 << (7 - jj);
                }
            }
        }

        const hash = sha256(entropy);
        const hashBits = MnemonicCode.bytesToBits(hash);

        for (let i = 0; i < checksumLengthBits; ++i) {
            if (concatBits[entropyLengthBits + i] !== hashBits[i]) {
                throw new MnemonicException.MnemonicChecksumException();
            }
        }

        return entropy;
    }

    /**
     * Convert entropy data to mnemonic word list.
     */
    public toMnemonic(entropy: Uint8Array): string[] {
        if (entropy.length % 4 > 0) {
            throw new MnemonicException.MnemonicLengthException("Entropy length not multiple of 32 bits.");
        }

        if (entropy.length === 0) {
            throw new MnemonicException.MnemonicLengthException("Entropy is empty.");
        }

        const hash = sha256(entropy);
        const hashBits = MnemonicCode.bytesToBits(hash);
        
        const entropyBits = MnemonicCode.bytesToBits(entropy);
        const checksumLengthBits = entropyBits.length / 32;

        const concatBits = new Array<boolean>(entropyBits.length + checksumLengthBits);
        for (let i = 0; i < entropyBits.length; i++) {
            concatBits[i] = entropyBits[i];
        }
        for (let i = 0; i < checksumLengthBits; i++) {
            concatBits[entropyBits.length + i] = hashBits[i];
        }

        const words: string[] = [];
        const nwords = concatBits.length / 11;
        for (let i = 0; i < nwords; ++i) {
            let index = 0;
            for (let j = 0; j < 11; ++j) {
                index <<= 1;
                if (concatBits[(i * 11) + j]) {
                    index |= 0x1;
                }
            }
            words.push(this.wordList[index]);
        }
            
        return words;        
    }

    /**
     * Check to see if a mnemonic word list is valid.
     */
    public check(words: string[]): void {
        this.toEntropy(words);
    }

    private static bytesToBits(data: Uint8Array): boolean[] {
        const bits = new Array<boolean>(data.length * 8);
        for (let i = 0; i < data.length; ++i) {
            for (let j = 0; j < 8; ++j) {
                bits[(i * 8) + j] = (data[i] & (1 << (7 - j))) !== 0;
            }
        }
        return bits;
    }
}

MnemonicCode.INSTANCE = new MnemonicCode();
