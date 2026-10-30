package com.aditya.album

// Pets in People: the user's dog or cat as a group of its own, named, merged, fixed and hidden like a person.
//
// Apple Photos (since iOS 17) and Google Photos tell individual cats and dogs apart with models made for animals. Album
// has none on the phone, so it uses what it does have: ML Kit's labels say which photos show a dog or a cat, and
// describe-it search's vectors (Embeddings) say which of those look alike. The vectors are of the whole photo, not the
// animal: ML Kit's base labeller gives no boxes. So pets need describe-it search on, look-alike pets (two black labs)
// may stay one group until the user splits them with "Not <name>", and a photo where the pet is small mostly stays out.
// Grouping is strict, and an unnamed group shows only once it spans PET_DAYS days, so a dog met once on a walk, or a
// burst of a friend's cat, isn't offered as the user's pet.

/** The animals People groups. Stored by name in people.db and pets.db: renaming a constant needs a migration. */
enum class Species(val labels: Set<String>) {
    // ML Kit base model labels; Labels.kt files the same breeds under Pets
    Dog(setOf("Dog", "Basset hound", "Cairn terrier", "Dalmatian", "Shetland sheepdog", "Shikoku", "Cavalier")),
    Cat(setOf("Cat", "Himalayan", "Ragdoll", "Sphynx", "Pixie-bob")),
}

/** A pet's "face": the whole photo, as pets have no boxes. Confirmed and cover spots of pets use it too. */
val WHOLE = FaceBox(0f, 0f, 1f, 1f)

/**
 * Automatic pet groups count up from here. Below 2^46, where person page ids start, and far above automatic face groups
 * (from 2^32), which would need 2^40 groups to get here: a cluster id alone says person or pet.
 */
const val PET_GROUPS = 1L shl 40

/** ML Kit this sure of a dog or cat label makes a photo a pet photo (its own default bar). */
const val PET_LABEL = 0.5f

/** ...and this sure, in a photo from the phone's camera, a strong one: only strong photos start a pet (see [petPhotos]). */
const val PET_SURE = 0.7f

/** An unnamed pet group shows only with photos on this many days: a pet is photographed again and again. */
const val PET_DAYS = 3

/**
 * Cosines between describe-it vectors of whole photos, not faces. ponytail: first guesses, not calibrated on pet
 * photos (none on this Mac): set well above how alike two photos of one breed in one room tend to be, so a group stays
 * one animal and the same pet in other places becomes "Same pet?" questions. Tune from the "pets:" breadcrumb.
 */
val PETS = Bars(same = 0.86f, attach = 0.84f, attachWeak = 0.9f, margin = 0.04f, suggest = 0.78f, min = 5)

/** The face id a pet photo goes by in People's screens and covers: negative, so it never meets a face's (from 1 up). */
fun petTileId(mediaId: Long) = -mediaId

/** A photo that shows a dog or a cat, how sure ML Kit is ([confidence]), and whether it's [weak] (may join, never starts a pet). */
data class PetPhoto(val mediaId: Long, val species: Species, val confidence: Float, val weak: Boolean)

/**
 * Pet photos from ML Kit's [scores] (label to photo to confidence): each photo's strongest dog or cat label, from
 * [PET_LABEL] up. [camera] says whether a photo was taken with this phone's camera; null leaves it out (a video, a
 * screenshot, gone from the phone). Forwards and downloads are weak, as is a label under [PET_SURE]: the cute dogs in
 * WhatsApp forwards are someone else's.
 */
fun petPhotos(scores: Map<String, Map<Long, Float>>, camera: (mediaId: Long) -> Boolean?): List<PetPhoto> {
    val best = HashMap<Long, Pair<Species, Float>>()
    for (species in Species.entries) for (label in species.labels) for ((id, confidence) in scores[label].orEmpty()) {
        if (confidence >= PET_LABEL && confidence > (best[id]?.second ?: 0f)) best[id] = species to confidence
    }
    return best.mapNotNull { (id, b) -> camera(id)?.let { PetPhoto(id, b.first, b.second, weak = !it || b.second < PET_SURE) } }.sortedBy { it.mediaId }
}

/** Pet photos as [groupPets] left them: every row to save, the next free automatic id, and "Same pet?" suggestions. */
class PetGrouping(val rows: List<PetRow>, val nextAuto: Long, val suggestions: List<Suggestion>)

/**
 * Groups [photos] into pets: cluster() per species with [PETS] bars, so a dog never joins a cat. [vectors] are the
 * describe-it vectors (unit length) of the photos encoded so far; a photo without one keeps its place in [old] (the last
 * run's rows) until it has. [pets] are the user's pets in people.db with their species, and [corrections] the user's
 * word as for faces: a photo they put in a pet takes that pet's species, whatever ML Kit says.
 */
fun groupPets(
    photos: List<PetPhoto>,
    vectors: Map<Long, FloatArray>,
    old: Map<Long, PetRow>,
    pets: Map<Long, Species>,
    corrections: Corrections,
    nextAuto: Long = PET_GROUPS,
    touched: Set<Long> = emptySet(),
): PetGrouping {
    // Later confirmations win, as in cluster()
    val confirmed = corrections.confirmed.sortedBy { it.order }.mapNotNull { c -> pets[c.personId]?.let { c.mediaId to it } }.toMap()
    var next = nextAuto
    val rows = ArrayList<PetRow>(photos.size)
    val suggestions = ArrayList<Suggestion>()
    for (species in Species.entries) {
        val mine = photos.filter { (confirmed[it.mediaId] ?: it.species) == species }
        if (mine.isEmpty()) continue
        // A row from the other species' run holds that species' group: this run starts it afresh
        fun kept(p: PetPhoto) = old[p.mediaId]?.takeIf { it.species == species }
        val faces = mine.mapNotNull { p ->
            vectors[p.mediaId]?.let { FaceVector(petTileId(p.mediaId), p.mediaId, WHOLE, it, kept(p)?.cluster ?: 0L, p.weak, kept(p)?.fit ?: 0f) }
        }
        val own = Corrections(pets.filterValues { it == species }.keys, corrections.confirmed, corrections.rejected, corrections.different)
        val result = cluster(faces, own, next, touched, PETS)
        next = result.nextAuto
        suggestions += result.suggestions
        for (p in mine) {
            val placed = result.changed[petTileId(p.mediaId)]
            rows += PetRow(p.mediaId, species, placed?.cluster ?: kept(p)?.cluster ?: 0L, placed?.fit ?: kept(p)?.fit ?: 0f, p.confidence)
        }
    }
    return PetGrouping(rows, next, suggestions.sortedByDescending { it.score })
}
