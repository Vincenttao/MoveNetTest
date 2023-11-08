/**
 * 这段代码是一个抽象类 AbstractTracker 的定义，它是用于对姿态估计数据进行追踪的。
 * 具体来说，它涉及到追踪视频序列中的人体姿态，并为每个姿态分配一个唯一的跟踪标识符。
 * 整个类是对人物追踪逻辑的抽象，实际的追踪逻辑需要在继承此抽象类的子类中实现。
 * 这可能包括特定的相似度计算方法和追踪更新策略。
 */

package com.alivehealth.motiontest.tracker

import com.alivehealth.motiontest.data.Person

abstract class AbstractTracker(val config: TrackerConfig) {
    /**
     * 定义了一个 maxAge 私有变量，用于设置追踪对象的最大寿命，单位是微秒（从配置中的毫秒转换而来）。
     */
    private val maxAge = config.maxAge * 1000 // convert milliseconds to microseconds
    private var nextTrackId = 0
    /**
    定义了一个 tracks 可变列表，用于存储当前所有的跟踪对象（Track 实例）。
    private set 表示外部代码不能直接修改这个列表，只能通过类中定义的方法修改。
    */
    var tracks = mutableListOf<Track>()
        private set

    /**
     * Computes pairwise similarity scores between detections and tracks, based
     * on detected features.
     * @param persons A list of detected person.
     * @returns A list of shape [num_det, num_tracks] with pairwise
     * similarity scores between detections and tracks.
     * 声明了一个抽象方法 computeSimilarity，该方法将接收一组 Person 对象，
     * 并计算每个 Person 与当前跟踪对象的相似度。
     */
    abstract fun computeSimilarity(persons: List<Person>): List<List<Float>>

    /**
     * Tracks person instances across frames based on detections.
     * @param persons A list of person
     * @param timestamp The current timestamp in microseconds
     * @return An updated list of persons with tracking id.
     * 定义了一个 apply 函数，它处理一组 Person 对象和一个时间戳，更新追踪信息，并返回更新后的 Person 列表。
     */
    fun apply(persons: List<Person>, timestamp: Long): List<Person> {
        tracks = filterOldTrack(timestamp).toMutableList()
        val simMatrix = computeSimilarity(persons)
        assignTrack(persons, simMatrix, timestamp)
        tracks = updateTrack().toMutableList()
        return persons
    }

    /**
     * Clear all track in list of tracks
     */
    fun reset() {
        tracks.clear()
    }

    /**
     * Return the next track id
     */
    private fun nextTrackID() = ++nextTrackId

    /**
     * 定义了一个 apply 函数，它处理一组 Person 对象和一个时间戳，
     * 更新追踪信息，并返回更新后的 Person 列表。
     * Performs a greedy optimization to link detections with tracks. The person
     * list is updated in place by providing an `id` property. If incoming
     * detections are not linked with existing tracks, new tracks will be created.
     * @param persons A list of detected person. It's assumed that persons are
     * sorted from most confident to least confident.
     * @param simMatrix A list of shape [num_det, num_tracks] with pairwise
     * similarity scores between detections and tracks.
     * @param timestamp The current timestamp in microseconds.
     */
    private fun assignTrack(persons: List<Person>, simMatrix: List<List<Float>>, timestamp: Long) {
        if ((simMatrix.size != persons.size) != (simMatrix[0].size != tracks.size)) {
            throw IllegalArgumentException(
                "Size of person array and similarity matrix does not match.")
        }

        val unmatchedTrackIndices = MutableList(tracks.size) { it }
        val unmatchedDetectionIndices = mutableListOf<Int>()

        for (detectionIndex in persons.indices) {
            // If the track list is empty, add the person's index
            // to unmatched detections to create a new track later.
            if (unmatchedTrackIndices.isEmpty()) {
                unmatchedDetectionIndices.add(detectionIndex)
                continue
            }

            // Assign the detection to the track which produces the highest pairwise
            // similarity score, assuming the score exceeds the minimum similarity
            // threshold.
            var maxTrackIndex = -1
            var maxSimilarity = -1f
            unmatchedTrackIndices.forEach { trackIndex ->
                val similarity = simMatrix[detectionIndex][trackIndex]
                if (similarity >= config.minSimilarity && similarity > maxSimilarity) {
                    maxTrackIndex = trackIndex
                    maxSimilarity = similarity
                }
            }
            if (maxTrackIndex >= 0) {
                val linkedTrack = tracks[maxTrackIndex]
                tracks[maxTrackIndex] =
                    createTrack(persons[detectionIndex], linkedTrack.person.id, timestamp)
                persons[detectionIndex].id = linkedTrack.person.id
                val index = unmatchedTrackIndices.indexOf(maxTrackIndex)
                unmatchedTrackIndices.removeAt(index)
            } else {
                unmatchedDetectionIndices.add(detectionIndex)
            }
        }

        // Spawn new tracks for all unmatched detections.
        unmatchedDetectionIndices.forEach { detectionIndex ->
            val newTrack = createTrack(persons[detectionIndex], timestamp = timestamp)
            tracks.add(newTrack)
            persons[detectionIndex].id = newTrack.person.id
        }
    }

    /**
     * Filters tracks based on their age.
     * @param timestamp The timestamp in microseconds
     */
    private fun filterOldTrack(timestamp: Long): List<Track> {
        return tracks.filter {
            timestamp - it.lastTimeStamp <= maxAge
        }
    }

    /**
     *  Sort the track list by timestamp (newer first)
     *  and return the track list with size equal to config.maxTracks
     */
    private fun updateTrack(): List<Track> {
        tracks.sortByDescending { it.lastTimeStamp }
        return tracks.take(config.maxTracks)
    }

    /**
     * Create a new track from person's information.
     * @param person A person
     * @param id The Id assign to the new track. If it is null, assign the next track id.
     * @param timestamp The timestamp in microseconds
     */
    private fun createTrack(person: Person, id: Int? = null, timestamp: Long): Track {
        return Track(
            person = Person(
                id = id ?: nextTrackID(),
                keyPoints = person.keyPoints,
                boundingBox = person.boundingBox,
                score = person.score
            ),
            lastTimeStamp = timestamp
        )
    }
}
