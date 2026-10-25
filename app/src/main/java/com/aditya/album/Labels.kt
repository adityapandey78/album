package com.aditya.album

import com.aditya.album.Category.Beach
import com.aditya.album.Category.Celebrations
import com.aditya.album.Category.City
import com.aditya.album.Category.Documents
import com.aditya.album.Category.Flowers
import com.aditya.album.Category.Food
import com.aditya.album.Category.Mountains
import com.aditya.album.Category.Nature
import com.aditya.album.Category.Night
import com.aditya.album.Category.Other
import com.aditya.album.Category.People
import com.aditya.album.Category.Pets
import com.aditya.album.Category.Sports
import com.aditya.album.Category.Sunsets
import com.aditya.album.Category.Vehicles
import java.util.EnumMap
import kotlin.math.abs

/**
 * What Explore shows under Things. A subject (food, a pet, a face) is what the photo is about; a scene is where it was taken.
 * Stored by name in the index: renaming a constant needs a database version bump (Room can't read the old name back), not just INDEX_VERSION. Declaration order breaks ties.
 */
enum class Category(val title: String, val scene: Boolean = false) {
    Documents("Documents and text"),
    Food("Food"),
    Pets("Pets"),
    People("Selfies and people"),
    Celebrations("Celebrations"),
    Sports("Sports"),
    Vehicles("Vehicles"),
    Flowers("Flowers and plants"),
    Beach("Beach", scene = true),
    Mountains("Mountains", scene = true),
    Sunsets("Sky and sunsets", scene = true),
    Night("Night", scene = true),
    City("Buildings and city", scene = true),
    Nature("Nature", scene = true),
    Other("Other"),
}

// A subject label must be at least this sure to beat the scene around it.
private const val SUBJECT_MIN = 0.7f

// ponytail: mean brightness below this reads as night. ML Kit has no "night" label; tune on real phone photos.
private const val DARK = 0.2f

/** ML Kit base model labels (developers.google.com/ml-kit/vision/image-labeling/label-map) that say something about a category. */
private val categoryOfLabel: Map<String, Category> = buildMap {
    fun add(category: Category, vararg labels: String) = labels.forEach { put(it, category) }
    add(
        Documents, "Paper", "Receipt", "Screenshot", "Poster", "Newspaper", "News", "Menu", "Passport", "Whiteboard",
        "Blackboard", "Web page", "Comics", "Presentation", "Money",
    )
    add(
        Food, "Food", "Cuisine", "Meal", "Lunch", "Supper", "Fast food", "Bento", "Cheeseburger", "Bread", "Cake", "Cookie",
        "Couscous", "Fruit", "Vegetable", "Gelato", "Hot dog", "Icing", "Pasteles", "Pho", "Pie", "Pizza", "Sushi", "Coffee",
        "Cappuccino", "Cola", "Juice", "Wine", "Alcohol", "Eating",
    )
    // Himalayan, Ragdoll, Sphynx and Pixie-bob are cat breeds; Cavalier and Shikoku are dogs
    add(
        Pets, "Pet", "Dog", "Cat", "Basset hound", "Cairn terrier", "Dalmatian", "Shetland sheepdog", "Shikoku", "Cavalier",
        "Himalayan", "Ragdoll", "Sphynx", "Pixie-bob", "Gerbil",
    )
    add(
        People, "Selfie", "Smile", "Laugh", "Person", "Baby", "Grandparent", "Dude", "Crowd", "Beard", "Moustache", "Eyelash",
        "Hair", "Bangs", "Lipstick", "Mouth", "Ear",
    )
    add(
        Celebrations, "Party", "Christmas", "Hanukkah", "Thanksgiving", "Santa claus", "Fireworks", "Sparkler", "Balloon",
        "Carnival", "Bride", "Groom", "Marriage", "Veil", "Prom", "Graduation", "Mortarboard", "Wreath", "Concert", "Deejay",
        "Dance", "Ballroom", "Nightclub",
    )
    add(
        Sports, "Sports", "Soccer", "Rugby", "Softball", "Badminton", "Polo", "Curling", "Gymnastics", "Archery", "Marathon",
        "Running", "Race", "Racing", "Cycling", "Competition", "Team", "Stadium", "Pitch", "Track", "Swimming", "Rowing",
        "Rafting", "Skateboard", "Skateboarder", "Longboard", "Unicycle", "Rodeo", "Bullfighting", "Jersey", "Scuba diving",
        "Wakeboarding", "Waterskiing", "Tubing", "Snorkeling",
    )
    add(
        Vehicles, "Vehicle", "Car", "Bus", "Train", "Motorcycle", "Bicycle", "Van", "Tractor", "Rickshaw", "Aircraft",
        "Airliner", "Airplane", "Aviation", "Helicopter", "Boat", "Sailboat", "Speedboat", "Canoe", "Kayak", "Skiff", "Brig",
        "Frigate", "Clipper", "Submarine", "Rocket", "Tire", "Wheel", "Bumper", "Windshield", "Odometer",
    )
    add(Flowers, "Flower", "Flora", "Petal", "Plant", "Garden", "Flowerpot")
    add(Beach, "Beach", "Sand", "Surfing", "Surfboard", "Windsurfing", "Swimwear", "Shell", "Pier", "Lighthouse", "Reef")
    add(Mountains, "Mountain", "Glacier", "Volcano", "Cliff", "Canyon", "Skiing", "Snowboarding", "Sledding", "Sled")
    add(Sunsets, "Sunset", "Sky", "Rainbow")
    add(Night, "Moon", "Star", "Aurora", "Nebula", "Comet", "Space", "Neon", "Lightning")
    add(
        City, "Building", "Skyscraper", "Skyline", "Tower", "Bridge", "Castle", "Cathedral", "Church", "Mosque", "Temple",
        "Palace", "Monument", "Museum", "Ruins", "Statue", "Roof", "Road", "Asphalt", "Infrastructure", "Construction",
        "Factory", "Dam", "Ferris wheel",
    )
    add(
        Nature, "Forest", "Jungle", "Field", "Prairie", "Lake", "River", "Waterfall", "Cave", "Desert", "Dune", "Swamp",
        "Underwater", "Rock", "Soil", "Branch", "Twig", "Safari", "Camping", "Backpacking", "Farm", "Ranch", "Barn", "Park",
        "Fog", "Ice", "Icicle", "Iceberg", "Storm", "Bird", "Bear", "Butterfly", "Insect", "Crocodile", "Turtle", "Penguin",
        "Seal", "Duck", "Waterfowl", "Horse", "Cattle", "Bull", "Herd", "Larva", "Pomacentridae",
    )
}

/**
 * One category per photo from its ML Kit labels and mean brightness (0 to 1).
 * A confident subject wins over the scene (a dog on a beach is a pet photo); a dark scene is Night.
 */
fun categoryOf(labels: List<Pair<String, Float>>, brightness: Float): Category {
    val best = EnumMap<Category, Float>(Category::class.java)
    for ((label, confidence) in labels) categoryOfLabel[label]?.let { best.merge(it, confidence, ::maxOf) }
    val subject = best.entries.filter { !it.key.scene && it.value >= SUBJECT_MIN }.maxByOrNull { it.value }?.key
    val strongest = best.entries.maxByOrNull { it.value }?.key
    return when {
        subject != null -> subject
        brightness < DARK && strongest?.scene != false -> Night
        else -> strongest ?: Other
    }
}

/** The ML Kit labels a category is made from, so search can rank a category's photos by how sure ML Kit was. */
fun labelsOf(category: Category): List<String> = categoryOfLabel.filterValues { it == category }.keys.toList()

/** Mean perceived brightness (0 to 1) of ARGB pixels, from a tiny downscale of the thumbnail. */
fun brightnessOf(pixels: IntArray): Float {
    if (pixels.isEmpty()) return 0f
    var sum = 0.0
    for (p in pixels) sum += 0.299 * (p shr 16 and 0xFF) + 0.587 * (p shr 8 and 0xFF) + 0.114 * (p and 0xFF)
    return (sum / pixels.size / 255).toFloat()
}

/** How likely a photo is to hold text worth reading. */
enum class TextChance { Sure, Maybe, None }

// Photos of screens often show text too; documents are covered by their category's labels
private val screenLabels = setOf("Computer", "Mobile phone", "Television", "Icon")

// Packaging, signs and book covers: often text, often not
private val textishLabels = setOf("Product", "Flag", "Clock", "Fiction", "Superhero", "Event", "Class", "School")

// A document or screen label this sure means text; ML Kit's own default bar
private const val SURE_LABEL = 0.5f

// ponytail: share of pixels with a sharp step to the next one (EDGE_STEP of 255 in brightness) above which a photo may
// hold text. On macOS's wallpaper photos (landscapes, at the indexer's 256 px) the median is 0.02 and about one in five
// pass 0.06; rendered documents, receipts, slides and chats score 0.18 to 0.35. Busy scenes pass too, which is why a
// Maybe only gets a quick look at the thumbnail first. Tune from the phone's breadcrumb ("looked at", "with some").
private const val EDGE_STEP = 40
private const val EDGE_MIN = 0.06f

/**
 * Whether text recognition is worth running on a photo. Sure: a screenshot, or a confident document or screen label
 * (read at full size). Maybe: a weaker such label, a label that often has text, or many sharp edges ([edges], see
 * [edgeDensity]): worth a quick look at the thumbnail. Text recognition is several times slower than labelling, so most
 * photos skip it.
 */
fun textChance(labels: List<Pair<String, Float>>, screenshot: Boolean, edges: Float): TextChance {
    fun texty(label: String) = categoryOfLabel[label] == Documents || label in screenLabels
    return when {
        screenshot || labels.any { (label, confidence) -> texty(label) && confidence >= SURE_LABEL } -> TextChance.Sure
        labels.any { (label, _) -> texty(label) || label in textishLabels } || edges >= EDGE_MIN -> TextChance.Maybe
        else -> TextChance.None
    }
}

/**
 * Share (0 to 1) of pixels whose brightness jumps by more than [EDGE_STEP] to the pixel on the right or below, in
 * [width] wide ARGB [pixels]. Printed and handwritten text is dense in these steps.
 */
fun edgeDensity(pixels: IntArray, width: Int): Float {
    if (width < 2 || pixels.size < width * 2) return 0f
    val height = pixels.size / width
    val luma = IntArray(width * height) { i ->
        val p = pixels[i]
        (299 * (p shr 16 and 0xFF) + 587 * (p shr 8 and 0xFF) + 114 * (p and 0xFF)) / 1000
    }
    var edges = 0
    for (y in 0 until height - 1) {
        for (x in 0 until width - 1) {
            val i = y * width + x
            if (abs(luma[i + 1] - luma[i]) > EDGE_STEP || abs(luma[i + width] - luma[i]) > EDGE_STEP) edges++
        }
    }
    return edges.toFloat() / ((width - 1) * (height - 1))
}

/**
 * Whether a quick read of a thumbnail found real text, not the odd letter ML Kit sees in a brick wall or leaves: a few
 * words' worth of letters and digits, with at least one word of three letters.
 */
fun looksLikeText(text: String?): Boolean = text != null && text.count { it.isLetterOrDigit() } >= 8 && word.containsMatchIn(text)

private val word = Regex("\\p{L}{3,}")

/** Android saves screenshots in a Screenshots folder (DCIM or Pictures) and names them Screenshot_... */
fun isScreenshot(relativePath: String, name: String) =
    relativePath.contains("screenshot", ignoreCase = true) || name.startsWith("screenshot", ignoreCase = true)
