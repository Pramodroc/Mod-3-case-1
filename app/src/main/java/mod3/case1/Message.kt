package mod3.case1

data class Message(
    val text: String,
    val isUser: Boolean,
    val sources: List<String>? = null
)

data class QueryRequest(val question: String)

data class QueryResponse(
    val answer: String,
    val sources: List<String>? = null
)

data class UploadResult(
    val total_documents: Int,
    val total_chunks: Int,
    val document_list: List<String>? = null
)

data class UploadResponse(
    val message: String,
    val result: UploadResult? = null
)

data class DocumentsResponse(
    val total_documents: Int,
    val total_chunks: Int,
    val documents: Map<String, Int>? = null
)

data class ResetResponse(
    val message: String
)
