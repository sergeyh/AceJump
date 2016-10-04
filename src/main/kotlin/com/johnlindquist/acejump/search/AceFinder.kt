package com.johnlindquist.acejump.search

import com.google.common.collect.BiMap
import com.google.common.collect.HashBiMap
import com.google.common.collect.LinkedListMultimap
import com.google.common.collect.Multimap
import com.intellij.find.FindManager
import com.intellij.find.FindModel
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.editor.impl.DocumentImpl
import com.intellij.openapi.editor.impl.EditorImpl
import com.intellij.util.EventDispatcher
import com.johnlindquist.acejump.keycommands.AceJumper
import java.awt.event.KeyEvent
import java.util.*
import javax.swing.event.ChangeEvent
import javax.swing.event.ChangeListener

class AceFinder(val findManager: FindManager, val editor: EditorImpl) {
  val document = editor.document as DocumentImpl
  val eventDispatcher = EventDispatcher.create(ChangeListener::class.java)
  val findModel: FindModel = findManager.findInFileModel.clone()
  var jumpLocations: Collection<Int> = emptyList()
  var tagMap: BiMap<String, Int> = HashBiMap.create()
  val maxTags = 26
  var unusedDigraphs: LinkedHashSet<String> = linkedSetOf()
  var tagLocations: HashSet<Int> = HashSet(maxTags)
  var qwertyAdjacentKeys =
    mapOf('1' to "12q", '2' to "23wq1", '3' to "34ew2", '4' to "45re3",
      '5' to "56tr4", '6' to "67yt5", '7' to "78uy6", '8' to "89iu7",
      '9' to "90oi8", '0' to "0po9", 'q' to "q12wa", 'w' to "w3esaq2",
      'e' to "e4rdsw3", 'r' to "r5tfde4", 't' to "t6ygfr5", 'y' to "y7uhgt6",
      'u' to "u8ijhy7", 'i' to "i9okju8", 'o' to "o0plki9", 'p' to "plo0",
      'a' to "aqwsz", 's' to "sedxzaw", 'd' to "drfcxse", 'f' to "ftgvcdr",
      'g' to "gyhbvft", 'h' to "hujnbgy", 'j' to "jikmnhu", 'k' to "kolmji",
      'l' to "lkop", 'z' to "zasx", 'x' to "xzsdc", 'c' to "cxdfv",
      'v' to "vcfgb", 'b' to "bvghn", 'n' to "nbhjm", 'm' to "mnjk")

  init {
    findModel.isFindAll = true
    findModel.isFromCursor = true
    findModel.isForward = true
    findModel.isRegularExpressions = false
    findModel.isWholeWordsOnly = false
    findModel.isCaseSensitive = false
    findModel.setSearchHighlighters(true)
    findModel.isPreserveCase = false
  }

  fun findText(text: String, keyEvent: KeyEvent?) {
    findModel.stringToFind = text
    unusedDigraphs = ('a'..'z').mapTo(linkedSetOf(), { "$it" })
    tagLocations = HashSet(maxTags)

    val application = ApplicationManager.getApplication()
    application.runReadAction(jump(keyEvent, text))

    application.invokeLater({
      if (text.isNotEmpty())
        eventDispatcher.multicaster.stateChanged(ChangeEvent("AceFinder"))
    })
  }

  val aceJumper = AceJumper(editor, document)
  private fun jump(keyEvent: KeyEvent?, text: String): () -> Unit {
    fun jumpToOffset(keyEvent: KeyEvent, offset: Int?) {
      if (offset == null)
        return

      if (keyEvent.isShiftDown && !keyEvent.isMetaDown) {
        aceJumper.setSelectionFromCaretToOffset(offset)
        aceJumper.moveCaret(offset)
      } else {
        aceJumper.moveCaret(offset)
      }

      if (targetModeEnabled) {
        aceJumper.selectWordAtCaret()
      }

      tagMap = HashBiMap.create()
    }

    fun isReadyToJump(text: String): Boolean {
      return (text.isNotEmpty() && !findModel.isRegularExpressions &&
        tagMap.containsKey(text.last().toString()) &&
        !tagMap.containsKey(text) &&
        document.charsSequence[tagMap[text.last().toString()]!!] != text.last())
      || (text.isNotEmpty() && !findModel.isRegularExpressions &&
        tagMap.containsKey(text.last().toString()) &&
        tagMap.containsKey(text) &&
        document.charsSequence[tagMap[text.last().toString()]!!] == text.last())
    }

    return {
      jumpLocations = determineJumpLocations()
      if (isReadyToJump(text)) {
        jumpToOffset(keyEvent!!, tagMap[text.last().toString()]!! - text.length)
      } else if (jumpLocations.size == 1) {
        jumpToOffset(keyEvent!!, jumpLocations.first() - text.length)
      }
    }
  }

  private fun determineJumpLocations(): Collection<Int> {
    fun getSitesToCheck(window: String): Iterable<Int> {
      if (findModel.stringToFind.isEmpty())
        return 0..(window.length - 2)

      val indicesToCheck = arrayListOf<Int>()
      var result = findManager.findString(window, 0, findModel)
      while (result.isStringFound) {
        indicesToCheck.add(result.endOffset)
        result = findManager.findString(window, result.endOffset, findModel)
      }

      return indicesToCheck
    }

    val (startIndex, endIndex) = getVisibleRange(editor)
    val fullText = document.charsSequence
    val window = fullText.substring(startIndex, endIndex)
    val sitesToCheck = getSitesToCheck(window).map { it + startIndex }
    if (sitesToCheck.size <= 1)
      return sitesToCheck
    val existingDigraphs = makeMap(fullText, sitesToCheck)
    tagMap = mapUniqueDigraphs(existingDigraphs)
    return tagMap.values
  }

  var targetModeEnabled = false
  fun toggleTargetMode(): Boolean {
    targetModeEnabled = !targetModeEnabled
    return targetModeEnabled
  }

  fun makeMap(text: CharSequence, sites: Iterable<Int>): Multimap<String, Int> {
    val stringToIndex = LinkedListMultimap.create<String, Int>()
    for (site in sites) {
      val (c1, c2) = Pair(text[site], text[site + 1])
      stringToIndex.put("$c1", site)
      unusedDigraphs.remove("$c1")
      if (c1.isLetterOrDigit() && c2.isLetterOrDigit()) {
        stringToIndex.put("$c1$c2", site)
        unusedDigraphs.remove("$c1$c2")
      }
    }

    return stringToIndex
  }

  fun mapUniqueDigraphs(digraphs: Multimap<String, Int>): BiMap<String, Int> {
    val newTagMap: BiMap<String, Int> = HashBiMap.create()
    fun mapTagToIndex(tag: String, index: Int) {
      newTagMap[tag] = index
      tagLocations.add(index)
    }

    fun hasNearbyTag(index: Int): Boolean {
      return ((index - 2)..(index + 2)).any { tagLocations.contains(it) }
    }

    for ((tag, indices) in digraphs.asMap()) {
      if (indices.size == 1 && !newTagMap.containsValue(indices.first())) {
        val tagIndex = indices.first() - findModel.stringToFind.length
        if (!hasNearbyTag(tagIndex))
          mapTagToIndex(tag.toLowerCase(), tagIndex)
      }
    }

    for (index in digraphs.values()) {
      if (unusedDigraphs.isEmpty())
        break
      if (!hasNearbyTag(index)) {
        mapTagToIndex(unusedDigraphs.first(), index)
        unusedDigraphs.remove(unusedDigraphs.first())
      }
    }

    return newTagMap
  }


  fun findText(text: Regexp, keyEvent: KeyEvent? = null) {
    findModel.isRegularExpressions = true
    findText(text.pattern, keyEvent)
    findModel.isRegularExpressions = false
  }
}