/**
  * Licensed to the Apache Software Foundation (ASF) under one or more
  * contributor license agreements.  See the NOTICE file distributed with
  * this work for additional information regarding copyright ownership.
  * The ASF licenses this file to You under the Apache License, Version 2.0
  * (the "License"); you may not use this file except in compliance with
  * the License.  You may obtain a copy of the License at
  *
  * http://www.apache.org/licenses/LICENSE-2.0
  *
  * Unless required by applicable law or agreed to in writing, software
  * distributed under the License is distributed on an "AS IS" BASIS,
  * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
  * See the License for the specific language governing permissions and
  * limitations under the License.
  */

package kafka.server.epoch
import java.io.File

import kafka.server.LogOffsetMetadata
import kafka.server.checkpoints.{LeaderEpochCheckpoint, LeaderEpochCheckpointFile}
import org.apache.kafka.common.requests.EpochEndOffset.{UNDEFINED_EPOCH, UNDEFINED_EPOCH_OFFSET}
import kafka.utils.TestUtils
import org.apache.kafka.common.TopicPartition
import org.junit.Assert._
import org.junit.{Before, Test}

import scala.collection.mutable.ListBuffer

/**
  * Unit test for the LeaderEpochFileCache.
  */
class LeaderEpochFileCacheTest {
  val tp = new TopicPartition("TestTopic", 5)
  var checkpoint: LeaderEpochCheckpoint = _

  @Test
  def shouldAddEpochAndMessageOffsetToCache() = {
    var leo = 0
    def leoFinder() = new LogOffsetMetadata(leo)

    //Given
    val cache = new LeaderEpochFileCache(tp, () => leoFinder, checkpoint)

    //When
    cache.assign(epoch = 2, offset = 10)
    leo = 11

    //Then
    assertEquals(2, cache.latestCommittedEpoch())
    assertEquals(EpochEntry(2, 10), cache.epochEntries()(0))
    assertEquals(11, cache.endOffsetFor(2)) //should match leo
  }
  
  @Test
  def shouldUpdateEpochWithLogEndOffset() = {
    var leo = 0
    def leoFinder() = new LogOffsetMetadata(leo)

    //Given
    leo = 9
    val cache = new LeaderEpochFileCache(tp, () => leoFinder, checkpoint)

    //When
    cache.appendProposal.proposeLeaderEpochChange(2)
    cache.appendProposal.maybeFlushUncommittedEpochs()

    //Then
    assertEquals(2, cache.latestCommittedEpoch())
    assertEquals(EpochEntry(2, 9), cache.epochEntries()(0))
  }

  @Test
  def shouldReturnLogEndOffsetIfLatestEpochRequested() = {
    var leo = 0
    def leoFinder() = new LogOffsetMetadata(leo)

    //Given
    val cache = new LeaderEpochFileCache(tp, () => leoFinder, checkpoint)

    //When just one epoch
    cache.assign(epoch = 2, offset = 11)
    cache.assign(epoch = 2, offset = 12)
    leo = 14

    //Then
    assertEquals(14, cache.endOffsetFor(2))
  }

  @Test
  def shouldReturnUndefinedOffsetIfUndefinedEpochRequested() = {
    def leoFinder() = new LogOffsetMetadata(0)

    //Given cache with some data on leader
    val cache = new LeaderEpochFileCache(tp, () => leoFinder, checkpoint)
    cache.assign(epoch = 2, offset = 11)
    cache.assign(epoch = 3, offset = 12)

    //When (say a bootstraping follower) sends request for UNDEFINED_EPOCH
    val offsetFor = cache.endOffsetFor(UNDEFINED_EPOCH)

    //Then
    assertEquals(UNDEFINED_EPOCH_OFFSET, offsetFor)
  }

  @Test
  def shouldNotOverwriteLogEndOffsetForALeaderEpochOnceItHasBeenAssigned() = {
    var leo = 0
    def leoFinder() = new LogOffsetMetadata(leo)

    //Given
    leo = 9
    val cache = new LeaderEpochFileCache(tp, () => leoFinder, checkpoint)

    cache.appendProposal.proposeLeaderEpochChange(2)
    cache.appendProposal.maybeFlushUncommittedEpochs()

    //When called again later
    leo = 10
    cache.appendProposal.proposeLeaderEpochChange(2)
    cache.appendProposal.maybeFlushUncommittedEpochs()

    //Then the offset should NOT have been updated
    assertEquals(9, cache.epochEntries()(0).startOffset)
  }

  @Test
  def shouldAllowLeaderEpochToChangeEvenIfOffsetDoesNot() = {
    var leo = 0
    def leoFinder() = new LogOffsetMetadata(leo)

    //Given
    leo = 9
    val cache = new LeaderEpochFileCache(tp, () => leoFinder, checkpoint)

    cache.appendProposal.proposeLeaderEpochChange(2)
    cache.appendProposal.maybeFlushUncommittedEpochs()

    //When update epoch with same leo
    cache.appendProposal.proposeLeaderEpochChange(3)
    cache.appendProposal.maybeFlushUncommittedEpochs()

    //Then the offset should NOT have been updated
    assertEquals(9, cache.endOffsetFor(3))
    assertEquals(9, cache.endOffsetFor(2))
    assertEquals(3, cache.latestCommittedEpoch())
  }
  
  @Test
  def shouldNotOverwriteOffsetForALeaderEpochOnceItHasBeenAssigned() = {
    //Given
    val cache = new LeaderEpochFileCache(tp, () => new LogOffsetMetadata(0), checkpoint)
    cache.assign(2, 6)

    //When called again later with a greater offset
    cache.assign(2, 10)

    //Then later update should have been ignored
    assertEquals(6, cache.epochEntries()(0).startOffset)
  }

  @Test
  def shouldReturnUnsupportedIfNoEpochRecorded(){
    def leoFinder() = new LogOffsetMetadata(0)

    //Given
    val cache = new LeaderEpochFileCache(tp, () => leoFinder, checkpoint)

    //Then
    assertEquals(UNDEFINED_EPOCH, cache.latestCommittedEpoch())
    assertEquals(UNDEFINED_EPOCH_OFFSET, cache.endOffsetFor(0))
  }

  @Test
  def shouldReturnUnsupportedIfRequestedEpochLessThanFirstEpoch(){
    def leoFinder() = new LogOffsetMetadata(0)

    //Given
    val cache = new LeaderEpochFileCache(tp, () => leoFinder, checkpoint)

    cache.assign(epoch = 5, offset = 11)
    cache.assign(epoch = 6, offset = 12)
    cache.assign(epoch = 7, offset = 13)

    //When
    val offset = cache.endOffsetFor(5 - 1)

    //Then
    assertEquals(UNDEFINED_EPOCH_OFFSET, offset)
  }

  @Test
  def shouldGetFirstOffsetOfSubsequentEpochWhenOffsetRequestedForPreviousEpoch() = {
    var leo = 0
    def leoFinder() = new LogOffsetMetadata(leo)

    //Given
    val cache = new LeaderEpochFileCache(tp, () => leoFinder, checkpoint)

    //When several epochs
    cache.assign(epoch = 1, offset = 11)
    cache.assign(epoch = 1, offset = 12)
    cache.assign(epoch = 2, offset = 13)
    cache.assign(epoch = 2, offset = 14)
    cache.assign(epoch = 3, offset = 15)
    cache.assign(epoch = 3, offset = 16)
    leo = 17

    //Then get the start offset of the next epoch
    assertEquals(15, cache.endOffsetFor(2))
  }

  @Test
  def shouldReturnNextAvailableEpochIfThereIsNoExactEpochForTheOneRequested(){
    def leoFinder() = new LogOffsetMetadata(0)

    //Given
    val cache = new LeaderEpochFileCache(tp, () => leoFinder, checkpoint)

    //When
    cache.assign(epoch = 0, offset = 10)
    cache.assign(epoch = 2, offset = 13)
    cache.assign(epoch = 4, offset = 17)

    //Then
    assertEquals(13, cache.endOffsetFor(requestedEpoch = 1))
    assertEquals(17, cache.endOffsetFor(requestedEpoch = 2))
  }

  @Test
  def shouldNotUpdateEpochAndStartOffsetIfItDidNotChange() = {
    def leoFinder() = new LogOffsetMetadata(0)

    //Given
    val cache = new LeaderEpochFileCache(tp, () => leoFinder, checkpoint)

    //When
    cache.assign(epoch = 2, offset = 6)
    cache.assign(epoch = 2, offset = 7)

    //Then
    assertEquals(1, cache.epochEntries.size)
    assertEquals(EpochEntry(2, 6), cache.epochEntries.toList(0))
  }

  @Test
  def shouldReturnInvalidOffsetIfEpochIsRequestedWhichIsNotCurrentlyTracked(): Unit = {
    val leo = 100
    def leoFinder() = new LogOffsetMetadata(leo)

    //Given
    val cache = new LeaderEpochFileCache(tp, () => leoFinder, checkpoint)

    //When
    cache.appendProposal.proposeLeaderEpochChange(epoch = 2)
    cache.appendProposal.epochForLeaderMessageAppend()

    //Then
    assertEquals(UNDEFINED_EPOCH_OFFSET, cache.endOffsetFor(3))
  }

  @Test
  def shouldSupportEpochsThatDoNotStartFromZero(): Unit = {
    var leo = 0
    def leoFinder() = new LogOffsetMetadata(leo)

    //Given
    val cache = new LeaderEpochFileCache(tp, () => leoFinder, checkpoint)

    //When
    cache.assign(epoch = 2, offset = 6)
    leo = 7

    //Then
    assertEquals(leo, cache.endOffsetFor(2))
    assertEquals(1, cache.epochEntries.size)
    assertEquals(EpochEntry(2, 6), cache.epochEntries()(0))
  }

  @Test
  def shouldPersistEpochsBetweenInstances(){
    def leoFinder() = new LogOffsetMetadata(0)
    val checkpointPath = TestUtils.tempFile().getAbsolutePath
    checkpoint = new LeaderEpochCheckpointFile(new File(checkpointPath))

    //Given
    val cache = new LeaderEpochFileCache(tp, () => leoFinder, checkpoint)
    cache.assign(epoch = 2, offset = 6)

    //When
    val checkpoint2 = new LeaderEpochCheckpointFile(new File(checkpointPath))
    val cache2 = new LeaderEpochFileCache(tp, () => leoFinder, checkpoint2)

    //Then
    assertEquals(1, cache2.epochEntries.size)
    assertEquals(EpochEntry(2, 6), cache2.epochEntries.toList(0))
  }

  @Test
  def shouldNotLetEpochGoBackwardsEvenIfMessageEpochsDo(): Unit = {
    var leo = 0
    def leoFinder() = new LogOffsetMetadata(leo)

    val cache = new LeaderEpochFileCache(tp, () => leoFinder, checkpoint)

    //Given
    cache.assign(epoch = 1, offset = 5); leo = 6
    cache.assign(epoch = 2, offset = 6); leo = 7

    //When we update an epoch in the past with an earlier offset
    cache.assign(epoch = 1, offset = 7); leo = 8

    //Then epoch should not be changed
    assertEquals(2, cache.latestCommittedEpoch())

    //Then end offset for epoch 1 shouldn't have changed
    assertEquals(6, cache.endOffsetFor(1))

    //Then end offset for epoch 2 has to be the offset of the epoch 1 message (I can't thing of a better option)
    assertEquals(8, cache.endOffsetFor(2))

    //Epoch history shouldn't have changed
    assertEquals(EpochEntry(1, 5), cache.epochEntries()(0))
    assertEquals(EpochEntry(2, 6), cache.epochEntries()(1))
  }

  @Test
  def shouldNotLetOffsetsGoBackwardsEvenIfEpochsProgress() = {
    def leoFinder() = new LogOffsetMetadata(0)

    //Given
    val cache = new LeaderEpochFileCache(tp, () => leoFinder, checkpoint)

    //When epoch goes forward but offset goes backwards
    cache.assign(epoch = 2, offset = 6)
    cache.assign(epoch = 3, offset = 5)

    //Then latter assign should be ignored
    assertEquals(EpochEntry(2, 6), cache.epochEntries.toList(0))
  }

  @Test
  def shouldIncreaseAndTrackEpochsAsLeadersChangeManyTimes(): Unit = {
    var leo = 0
    def leoFinder() = new LogOffsetMetadata(leo)

    //Given
    val cache = new LeaderEpochFileCache(tp, () => leoFinder, checkpoint)
    cache.appendProposal.proposeLeaderEpochChange(epoch = 0) //leo=0
    cache.appendProposal.maybeFlushUncommittedEpochs()

    //When
    cache.appendProposal.proposeLeaderEpochChange(epoch = 1) //leo=0
    cache.appendProposal.maybeFlushUncommittedEpochs()

    //Then epoch should go up
    assertEquals(1, cache.latestCommittedEpoch())
    //offset for 1 should still be 0
    assertEquals(0, cache.endOffsetFor(1))
    //offset for 0 should the start offset of epoch(1) => 0
    assertEquals(0, cache.endOffsetFor(0))

    //When we write 5 messages as epoch 1
    leo = 5

    //Then end offset for epoch(1) should be leo => 5
    assertEquals(5, cache.endOffsetFor(1))
    //Epoch(0) should still show the start offset for Epoch(1) => 0
    assertEquals(0, cache.endOffsetFor(0))

    //When
    cache.appendProposal.proposeLeaderEpochChange(epoch = 2) //leo=5
    cache.appendProposal.maybeFlushUncommittedEpochs()
    leo = 10 //write another 5 messages

    //Then end offset for epoch(2) should be leo => 10
    assertEquals(10, cache.endOffsetFor(2))

    //end offset for epoch(1) should be the start offset of epoch(2) => 5
    assertEquals(5, cache.endOffsetFor(1))

    //epoch (0) should still be 0
    assertEquals(0, cache.endOffsetFor(0))
  }

  @Test
  def shouldIncreaseAndTrackEpochsAsFollowerReceivesManyMessages(): Unit = {
    var leo = 0
    def leoFinder() = new LogOffsetMetadata(leo)

    //When new
    val cache = new LeaderEpochFileCache(tp, () => leoFinder, checkpoint)

    //When Messages come in
    cache.assign(epoch = 0, offset = 0); leo = 1
    cache.assign(epoch = 0, offset = 1); leo = 2
    cache.assign(epoch = 0, offset = 2); leo = 3

    //Then epoch should stay, offsets should grow
    assertEquals(0, cache.latestCommittedEpoch())
    assertEquals(leo, cache.endOffsetFor(0))

    //When messages arrive with greater epoch
    cache.assign(epoch = 1, offset = 3); leo = 4
    cache.assign(epoch = 1, offset = 4); leo = 5
    cache.assign(epoch = 1, offset = 5); leo = 6

    assertEquals(1, cache.latestCommittedEpoch())
    assertEquals(leo, cache.endOffsetFor(1))

    //When
    cache.assign(epoch = 2, offset = 6); leo = 7
    cache.assign(epoch = 2, offset = 7); leo = 8
    cache.assign(epoch = 2, offset = 8); leo = 9

    assertEquals(2, cache.latestCommittedEpoch())
    assertEquals(leo, cache.endOffsetFor(2))

    //Older epochs should return the start offset of the first message in the subsequent epoch.
    assertEquals(3, cache.endOffsetFor(0))
    assertEquals(6, cache.endOffsetFor(1))
  }

  @Test
  def shouldDropEntriesOnEpochBoundaryWhenRemovingLatestEntries(): Unit = {
    def leoFinder() = new LogOffsetMetadata(0)

    //Given
    val cache = new LeaderEpochFileCache(tp, () => leoFinder, checkpoint)
    cache.assign(epoch = 2, offset = 6)
    cache.assign(epoch = 3, offset = 8)
    cache.assign(epoch = 4, offset = 11)

    //When clear latest on epoch boundary
    cache.clearLatest(offset = 8)

    //Then should remove two latest epochs (remove is inclusive)
    assertEquals(ListBuffer(EpochEntry(2, 6)), cache.epochEntries)
  }

  @Test
  def shouldPreserveResetOffsetOnClearEarliestIfOneExists(): Unit = {
    def leoFinder() = new LogOffsetMetadata(0)

    //Given
    val cache = new LeaderEpochFileCache(tp, () => leoFinder, checkpoint)
    cache.assign(epoch = 2, offset = 6)
    cache.assign(epoch = 3, offset = 8)
    cache.assign(epoch = 4, offset = 11)

    //When reset to offset ON epoch boundary
    cache.clearEarliest(offset = 8)

    //Then should preserve (3, 8)
    assertEquals(ListBuffer(EpochEntry(3, 8), EpochEntry(4, 11)), cache.epochEntries)
  }

  @Test
  def shouldUpdateSavedOffsetWhenOffsetToClearToIsBetweenEpochs(): Unit = {
    def leoFinder() = new LogOffsetMetadata(0)

    //Given
    val cache = new LeaderEpochFileCache(tp, () => leoFinder, checkpoint)
    cache.assign(epoch = 2, offset = 6)
    cache.assign(epoch = 3, offset = 8)
    cache.assign(epoch = 4, offset = 11)

    //When reset to offset BETWEEN epoch boundaries
    cache.clearEarliest(offset = 9)

    //Then we should retain epoch 3, but update it's offset to 9 as 8 has been removed
    assertEquals(ListBuffer(EpochEntry(3, 9), EpochEntry(4, 11)), cache.epochEntries)
  }

  @Test
  def shouldNotClearAnythingIfOffsetToEarly(): Unit = {
    def leoFinder() = new LogOffsetMetadata(0)

    //Given
    val cache = new LeaderEpochFileCache(tp, () => leoFinder, checkpoint)
    cache.assign(epoch = 2, offset = 6)
    cache.assign(epoch = 3, offset = 8)
    cache.assign(epoch = 4, offset = 11)

    //When reset to offset before first epoch offset
    cache.clearEarliest(offset = 1)

    //Then nothing should change
    assertEquals(ListBuffer(EpochEntry(2, 6),EpochEntry(3, 8), EpochEntry(4, 11)), cache.epochEntries)
  }

  @Test
  def shouldNotClearAnythingIfOffsetToFirstOffset(): Unit = {
    def leoFinder() = new LogOffsetMetadata(0)

    //Given
    val cache = new LeaderEpochFileCache(tp, () => leoFinder, checkpoint)
    cache.assign(epoch = 2, offset = 6)
    cache.assign(epoch = 3, offset = 8)
    cache.assign(epoch = 4, offset = 11)

    //When reset to offset on earliest epoch boundary
    cache.clearEarliest(offset = 6)

    //Then nothing should change
    assertEquals(ListBuffer(EpochEntry(2, 6),EpochEntry(3, 8), EpochEntry(4, 11)), cache.epochEntries)
  }

  @Test
  def shouldRetainLatestEpochOnClearAllEarliest(): Unit = {
    def leoFinder() = new LogOffsetMetadata(0)

    //Given
    val cache = new LeaderEpochFileCache(tp, () => leoFinder, checkpoint)
    cache.assign(epoch = 2, offset = 6)
    cache.assign(epoch = 3, offset = 8)
    cache.assign(epoch = 4, offset = 11)

    //When
    cache.clearEarliest(offset = 11)

    //Then retain the last
    assertEquals(ListBuffer(EpochEntry(4, 11)), cache.epochEntries)
  }

  @Test
  def shouldUpdateOffsetBetweenEpochBoundariesOnClearEarliest(): Unit = {
    def leoFinder() = new LogOffsetMetadata(0)

    //Given
    val cache = new LeaderEpochFileCache(tp, () => leoFinder, checkpoint)
    cache.assign(epoch = 2, offset = 6)
    cache.assign(epoch = 3, offset = 8)
    cache.assign(epoch = 4, offset = 11)

    //When we clear from a postition between offset 8 & offset 11
    cache.clearEarliest(offset = 9)

    //Then we should update the middle epoch entry's offset
    assertEquals(ListBuffer(EpochEntry(3, 9), EpochEntry(4, 11)), cache.epochEntries)
  }

  @Test
  def shouldUpdateOffsetBetweenEpochBoundariesOnClearEarliest2(): Unit = {
    def leoFinder() = new LogOffsetMetadata(0)

    //Given
    val cache = new LeaderEpochFileCache(tp, () => leoFinder, checkpoint)
    cache.assign(epoch = 0, offset = 0)
    cache.assign(epoch = 1, offset = 7)
    cache.assign(epoch = 2, offset = 10)

    //When we clear from a postition between offset 0 & offset 7
    cache.clearEarliest(offset = 5)

    //Then we should keeep epoch 0 but update the offset appropriately
    assertEquals(ListBuffer(EpochEntry(0,5), EpochEntry(1, 7), EpochEntry(2, 10)), cache.epochEntries)
  }

  @Test
  def shouldRetainLatestEpochOnClearAllEarliestAndUpdateItsOffset(): Unit = {
    def leoFinder() = new LogOffsetMetadata(0)

    //Given
    val cache = new LeaderEpochFileCache(tp, () => leoFinder, checkpoint)
    cache.assign(epoch = 2, offset = 6)
    cache.assign(epoch = 3, offset = 8)
    cache.assign(epoch = 4, offset = 11)

    //When reset to offset beyond last epoch
    cache.clearEarliest(offset = 15)

    //Then update the last
    assertEquals(ListBuffer(EpochEntry(4, 15)), cache.epochEntries)
  }

  @Test
  def shouldDropEntriesBetweenEpochBoundaryWhenRemovingNewest(): Unit = {
    def leoFinder() = new LogOffsetMetadata(0)

    //Given
    val cache = new LeaderEpochFileCache(tp, () => leoFinder, checkpoint)
    cache.assign(epoch = 2, offset = 6)
    cache.assign(epoch = 3, offset = 8)
    cache.assign(epoch = 4, offset = 11)

    //When reset to offset BETWEEN epoch boundaries
    cache.clearLatest(offset = 9)

    //Then should keep the preceding epochs
    assertEquals(3, cache.latestCommittedEpoch())
    assertEquals(ListBuffer(EpochEntry(2, 6), EpochEntry(3, 8)), cache.epochEntries)
  }

  @Test
  def shouldClearAllEntries(): Unit = {
    def leoFinder() = new LogOffsetMetadata(0)

    //Given
    val cache = new LeaderEpochFileCache(tp, () => leoFinder, checkpoint)
    cache.assign(epoch = 2, offset = 6)
    cache.assign(epoch = 3, offset = 8)
    cache.assign(epoch = 4, offset = 11)

    //When 
    cache.clear()

    //Then 
    assertEquals(0, cache.epochEntries.size)
  }

  @Test
  def shouldNotResetEpochHistoryHeadIfUndefinedPassed(): Unit = {
    def leoFinder() = new LogOffsetMetadata(0)

    //Given
    val cache = new LeaderEpochFileCache(tp, () => leoFinder, checkpoint)
    cache.assign(epoch = 2, offset = 6)
    cache.assign(epoch = 3, offset = 8)
    cache.assign(epoch = 4, offset = 11)

    //When reset to offset on epoch boundary
    cache.clearLatest(offset = UNDEFINED_EPOCH_OFFSET)

    //Then should do nothing
    assertEquals(3, cache.epochEntries.size)
  }

  @Test
  def shouldNotResetEpochHistoryTailIfUndefinedPassed(): Unit = {
    def leoFinder() = new LogOffsetMetadata(0)

    //Given
    val cache = new LeaderEpochFileCache(tp, () => leoFinder, checkpoint)
    cache.assign(epoch = 2, offset = 6)
    cache.assign(epoch = 3, offset = 8)
    cache.assign(epoch = 4, offset = 11)

    //When reset to offset on epoch boundary
    cache.clearEarliest(offset = UNDEFINED_EPOCH_OFFSET)

    //Then should do nothing
    assertEquals(3, cache.epochEntries.size)
  }

  @Test
  def shouldFetchLatestEpochOfEmptyCache(): Unit = {
    //Given
    def leoFinder() = new LogOffsetMetadata(0)

    //When
    val cache = new LeaderEpochFileCache(tp, () => leoFinder, checkpoint)

    //Then
    assertEquals(-1, cache.latestCommittedEpoch)
  }

  @Test
  def shouldFetchEndOffsetOfEmptyCache(): Unit = {
    //Given
    def leoFinder() = new LogOffsetMetadata(0)

    //When
    val cache = new LeaderEpochFileCache(tp, () => leoFinder, checkpoint)

    //Then
    assertEquals(-1, cache.endOffsetFor(7))
  }

  @Test
  def shouldClearEarliestOnEmptyCache(): Unit = {
    //Given
    def leoFinder() = new LogOffsetMetadata(0)

    //When
    val cache = new LeaderEpochFileCache(tp, () => leoFinder, checkpoint)

    //Then
    cache.clearEarliest(7)
  }

  @Test
  def shouldClearLatestOnEmptyCache(): Unit = {
    //Given
    def leoFinder() = new LogOffsetMetadata(0)

    //When
    val cache = new LeaderEpochFileCache(tp, () => leoFinder, checkpoint)

    //Then
    cache.clearLatest(7)
  }

  @Test
  def shouldUpdateEpochCacheOnLeadershipChangeThenCommit(): Unit ={
    //Given
    def leoFinder() = new LogOffsetMetadata(5)
    val cache = new LeaderEpochFileCache(tp, () => leoFinder, checkpoint)

    //When
    cache.appendProposal.proposeLeaderEpochChange(2)

    //Then
    assertEquals(UNDEFINED_EPOCH, cache.latestCommittedEpoch())

    //When
    cache.appendProposal.maybeFlushUncommittedEpochs()

    //Then should have saved epoch
    assertEquals(2, cache.latestCommittedEpoch())

    //Then should have applied LEO to epoch
    assertEquals(5, cache.endOffsetFor(2))
  }

  @Before
  def setUp() {
    checkpoint = new LeaderEpochCheckpointFile(TestUtils.tempFile())
  }
}