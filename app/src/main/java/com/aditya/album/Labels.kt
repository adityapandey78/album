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

/** Mean perceived brightness (0 to 1) of ARGB pixels, from a tiny downscale of the thumbnail. */
fun brightnessOf(pixels: IntArray): Float {
    if (pixels.isEmpty()) return 0f
    var sum = 0.0
    for (p in pixels) sum += 0.299 * (p shr 16 and 0xFF) + 0.587 * (p shr 8 and 0xFF) + 0.114 * (p and 0xFF)
    return (sum / pixels.size / 255).toFloat()
}
