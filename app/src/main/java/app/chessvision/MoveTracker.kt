package app.chessvision

/**
 * Tracks a position by comparing two recognized boards. A move is accepted only
 * when the complete board difference matches a geometrically valid chess move.
 * Board indexing is a1=0 ... h8=63; uppercase pieces are White.
 * This is deliberately fail-closed: uncertain/multiple changes do not advance the turn.
 */
class MoveTracker {
    data class Update(val uci: String, val sanLike: String, val turnAfter: Char)

    var turn: Char = 'w'
        private set
    var lastError: String? = null
        private set

    private var previous: CharArray? = null
    private var castling = "KQkq"
    private var ep = "-"
    private var halfmove = 0
    private var fullmove = 1

    fun reset(initial: CharArray, initialTurn: Char = 'w') {
        previous = initial.copyOf()
        turn = initialTurn
        castling = buildString {
            if (initial[4] == 'K' && initial[7] == 'R') append('K')
            if (initial[4] == 'K' && initial[0] == 'R') append('Q')
            if (initial[60] == 'k' && initial[63] == 'r') append('k')
            if (initial[60] == 'k' && initial[56] == 'r') append('q')
        }.ifEmpty { "-" }
        ep = "-"; halfmove = 0; fullmove = 1; lastError = null
    }

    fun update(current: CharArray): Update? {
        val old = previous ?: run { reset(current); return null }
        if (old.contentEquals(current)) return null
        val candidates = ArrayList<Move>()
        for (from in 0 until 64) {
            val piece = old[from]
            if (piece == ' ' || color(piece) != turn) continue
            for (to in 0 until 64) {
                if (from == to || old[to] == piece || colorOrNull(old[to]) == color(piece)) continue
                val rankTo = to / 8
                val promotes = piece.lowercaseChar() == 'p' && (rankTo == 0 || rankTo == 7)
                if (promotes) {
                    for (promotion in charArrayOf('q','r','b','n')) {
                        val promoted = if (piece.isUpperCase()) promotion.uppercaseChar() else promotion
                        if (matchesOrdinary(old, current, from, to, promoted)) candidates += Move(from, to, promoted)
                    }
                } else if (matchesOrdinary(old, current, from, to, piece)) {
                    candidates += Move(from, to, null)
                }
            }
        }
        addSpecialCandidates(old, current, candidates)
        val unique = candidates.distinctBy { "${it.from}:${it.to}:${it.promotion}:${it.rookFrom}" }
        if (unique.size != 1) {
            lastError = when {
                unique.isEmpty() -> "Изменение доски не похоже на один допустимый ход"
                else -> "Неоднозначный ход: найдено ${unique.size} вариантов"
            }
            return null
        }
        val move = unique.single()
        val moving = old[move.from]
        val captured = if (move.enPassant) old[move.to + if (moving.isUpperCase()) -8 else 8] else old[move.to]
        val fromText = square(move.from); val toText = square(move.to)
        val uci = fromText + toText + (move.promotion?.lowercaseChar()?.toString() ?: "")
        updateRights(moving, move.from, move.to, captured)
        ep = if (moving.lowercaseChar() == 'p' && kotlin.math.abs(move.to - move.from) == 16) square((move.from + move.to) / 2) else "-"
        halfmove = if (moving.lowercaseChar() == 'p' || captured != ' ') 0 else halfmove + 1
        if (turn == 'b') fullmove++
        turn = if (turn == 'w') 'b' else 'w'
        previous = current.copyOf()
        lastError = null
        val label = "$fromText–$toText" + if (move.promotion != null) "=${move.promotion.uppercaseChar()}" else if (captured != ' ') " взятие" else ""
        return Update(uci, label, turn)
    }

    fun fen(board: CharArray): String {
        val placement = StringBuilder()
        for (rank in 7 downTo 0) {
            var empty = 0
            for (file in 0..7) {
                val p = board[rank * 8 + file]
                if (p == ' ') empty++ else {
                    if (empty > 0) { placement.append(empty); empty = 0 }
                    placement.append(p)
                }
            }
            if (empty > 0) placement.append(empty)
            if (rank > 0) placement.append('/')
        }
        return "$placement $turn $castling $ep $halfmove $fullmove"
    }

    private data class Move(
        val from: Int, val to: Int, val promotion: Char? = null,
        val rookFrom: Int = -1, val rookTo: Int = -1, val enPassant: Boolean = false
    )

    private fun matchesOrdinary(old: CharArray, now: CharArray, from: Int, to: Int, placed: Char): Boolean {
        if (!geometryValid(old, from, to)) return false
        val result = old.copyOf(); result[from] = ' '; result[to] = placed
        return result.contentEquals(now)
    }

    private fun geometryValid(b: CharArray, from: Int, to: Int): Boolean {
        val p = b[from]; val target = b[to]
        if (p == ' ' || colorOrNull(target) == color(p)) return false
        val fx = from % 8; val fy = from / 8; val tx = to % 8; val ty = to / 8
        val dx = tx - fx; val dy = ty - fy
        val ax = kotlin.math.abs(dx); val ay = kotlin.math.abs(dy)
        return when (p.lowercaseChar()) {
            'n' -> (ax == 1 && ay == 2) || (ax == 2 && ay == 1)
            'k' -> ax <= 1 && ay <= 1
            'b' -> ax == ay && clearPath(b, from, to)
            'r' -> (dx == 0 || dy == 0) && clearPath(b, from, to)
            'q' -> ((ax == ay) || dx == 0 || dy == 0) && clearPath(b, from, to)
            'p' -> {
                val dir = if (p.isUpperCase()) 1 else -1
                when {
                    dx == 0 && dy == dir -> target == ' '
                    dx == 0 && dy == 2 * dir && fy == if (p.isUpperCase()) 1 else 6 ->
                        target == ' ' && b[from + 8 * dir] == ' '
                    ax == 1 && dy == dir -> target != ' '
                    else -> false
                }
            }
            else -> false
        }
    }

    private fun clearPath(b: CharArray, from: Int, to: Int): Boolean {
        val dx = (to % 8 - from % 8).compareTo(0)
        val dy = (to / 8 - from / 8).compareTo(0)
        var x = from % 8 + dx; var y = from / 8 + dy
        while (x != to % 8 || y != to / 8) {
            if (b[y * 8 + x] != ' ') return false
            x += dx; y += dy
        }
        return true
    }

    private fun addSpecialCandidates(old: CharArray, now: CharArray, out: MutableList<Move>) {
        // Castling: king and rook must both move to their standard destination squares.
        val base = if (turn == 'w') 0 else 56
        val king = if (turn == 'w') 'K' else 'k'
        val rook = if (turn == 'w') 'R' else 'r'
        if (old[base + 4] == king) {
            for ((rookFrom, kingTo, rookTo, right) in listOf(
                listOf(base + 7, base + 6, base + 5, if (turn == 'w') 'K'.code else 'k'.code),
                listOf(base, base + 2, base + 3, if (turn == 'w') 'Q'.code else 'q'.code)
            )) {
                if (old[rookFrom] != rook || (right.toChar() !in castling)) continue
                val between = if (rookFrom > base + 4) intArrayOf(base + 5, base + 6) else intArrayOf(base + 1, base + 2, base + 3)
                if (between.any { old[it] != ' ' }) continue
                val r = old.copyOf(); r[base + 4] = ' '; r[rookFrom] = ' '; r[kingTo] = king; r[rookTo] = rook
                if (r.contentEquals(now)) out += Move(base + 4, kingTo, null, rookFrom, rookTo)
            }
        }
        // En passant: pawn moves diagonally to an empty square while the adjacent pawn disappears.
        val dir = if (turn == 'w') 1 else -1
        for (from in 0 until 64) {
            val pawn = old[from]
            if (pawn.lowercaseChar() != 'p' || color(pawn) != turn) continue
            val fx = from % 8; val fy = from / 8
            val ty = fy + dir
            if (ty !in 0..7) continue
            for (tx in intArrayOf(fx - 1, fx + 1)) {
                if (tx !in 0..7) continue
                val to = ty * 8 + tx
                val cap = to - 8 * dir
                if (old[to] != ' ' || old[cap].lowercaseChar() != 'p' || color(old[cap]) == turn) continue
                val r = old.copyOf(); r[from] = ' '; r[cap] = ' '; r[to] = pawn
                if (r.contentEquals(now)) out += Move(from, to, null, enPassant = true)
            }
        }
    }

    private fun updateRights(piece: Char, from: Int, to: Int, captured: Char) {
        val rights = castling.toSet().toMutableSet()
        when (piece) {
            'K' -> { rights.remove('K'); rights.remove('Q') }
            'k' -> { rights.remove('k'); rights.remove('q') }
            'R' -> if (from == 0) rights.remove('Q') else if (from == 7) rights.remove('K')
            'r' -> if (from == 56) rights.remove('q') else if (from == 63) rights.remove('k')
        }
        when (to) {
            0 -> if (captured == 'R') rights.remove('Q')
            7 -> if (captured == 'R') rights.remove('K')
            56 -> if (captured == 'r') rights.remove('q')
            63 -> if (captured == 'r') rights.remove('k')
        }
        castling = "KQkq".filter { it in rights }.ifEmpty { "-" }
    }

    private fun square(i: Int) = "${'a' + i % 8}${'1' + i / 8}"
    private fun color(c: Char) = if (c.isUpperCase()) 'w' else 'b'
    private fun colorOrNull(c: Char): Char? = if (c == ' ') null else color(c)
}
