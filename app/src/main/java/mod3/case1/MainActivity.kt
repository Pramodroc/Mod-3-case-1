package mod3.case1

import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import android.view.View
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import kotlinx.coroutines.launch
import mod3.case1.databinding.ActivityMainBinding
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.MultipartBody
import okhttp3.RequestBody.Companion.asRequestBody
import java.io.File
import java.io.FileOutputStream

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private val chatAdapter = ChatAdapter(mutableListOf())
    private var isDocumentLoaded = false

    // Multi-PDF Picker launcher
    private val pickPdfs = registerForActivityResult(
        ActivityResultContracts.GetMultipleContents()
    ) { uris: List<Uri> ->
        if (uris.isNotEmpty()) {
            uploadPdfs(uris)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setupRecyclerView()

        binding.btnUpload.setOnClickListener {
            pickPdfs.launch("application/pdf")
        }

        binding.btnClear.setOnClickListener {
            clearDocuments()
        }

        binding.btnSend.setOnClickListener {
            val question = binding.etQuestion.text.toString().trim()
            if (question.isEmpty()) return@setOnClickListener
            if (!isDocumentLoaded) {
                Toast.makeText(this, "Please upload at least one PDF first", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            sendQuestion(question)
            binding.etQuestion.text.clear()
        }

        checkExistingDocuments()
    }

    private fun setupRecyclerView() {
        binding.rvChat.layoutManager = LinearLayoutManager(this)
        binding.rvChat.adapter = chatAdapter
    }

    private fun checkExistingDocuments() {
        lifecycleScope.launch {
            try {
                val response = RetrofitClient.api.getDocuments()
                if (response.isSuccessful) {
                    val body = response.body()
                    val totalDocs = body?.total_documents ?: 0
                    if (totalDocs > 0) {
                        isDocumentLoaded = true
                        val docNames = body?.documents?.keys?.joinToString(", ") ?: ""
                        binding.tvStatus.text = "Loaded ($totalDocs doc(s)): $docNames"
                    } else {
                        isDocumentLoaded = false
                        binding.tvStatus.text = "No documents uploaded"
                    }
                }
            } catch (e: Exception) {
                binding.tvStatus.text = "Backend status: Disconnected (Start server on port 8000)"
            }
        }
    }

    /** Copies picked Uris to temp files, then uploads to backend. */
    private fun uploadPdfs(uris: List<Uri>) {
        binding.tvStatus.text = "Uploading ${uris.size} document(s)..."
        setLoading(true)

        lifecycleScope.launch {
            try {
                val parts = mutableListOf<MultipartBody.Part>()
                for (uri in uris) {
                    val fileName = queryFileName(uri)
                    val tempFile = copyUriToCache(uri, fileName)
                    val requestBody = tempFile.asRequestBody("application/pdf".toMediaTypeOrNull())
                    parts.add(MultipartBody.Part.createFormData("files", tempFile.name, requestBody))
                }

                val response = RetrofitClient.api.uploadDocuments(parts)

                if (response.isSuccessful) {
                    isDocumentLoaded = true
                    val result = response.body()?.result
                    val totalDocs = result?.total_documents ?: uris.size
                    val totalChunks = result?.total_chunks ?: 0
                    val docsList = result?.document_list?.joinToString(", ") ?: ""

                    binding.tvStatus.text = "Loaded ($totalDocs doc(s), $totalChunks chunks): $docsList"
                    chatAdapter.addMessage(
                        Message("📄 Indexed ${uris.size} document(s) successfully! Total $totalDocs document(s) loaded. Ask me anything!", false)
                    )
                    binding.rvChat.scrollToPosition(chatAdapter.itemCount - 1)
                } else {
                    binding.tvStatus.text = "Upload failed"
                    Toast.makeText(
                        this@MainActivity,
                        "Server error: ${response.code()}",
                        Toast.LENGTH_LONG
                    ).show()
                }
            } catch (e: Exception) {
                binding.tvStatus.text = "Upload error"
                Toast.makeText(this@MainActivity, "Error: ${e.message}", Toast.LENGTH_LONG).show()
            } finally {
                setLoading(false)
            }
        }
    }

    private fun sendQuestion(question: String) {
        chatAdapter.addMessage(Message(question, isUser = true))
        binding.rvChat.scrollToPosition(chatAdapter.itemCount - 1)
        setLoading(true)

        lifecycleScope.launch {
            try {
                val response = RetrofitClient.api.askQuestion(QueryRequest(question))
                if (response.isSuccessful) {
                    val body = response.body()
                    val answer = body?.answer ?: "Empty response."
                    chatAdapter.addMessage(Message(answer, isUser = false, sources = body?.sources))
                } else {
                    chatAdapter.addMessage(Message("Server error (${response.code()})", isUser = false))
                }
                binding.rvChat.scrollToPosition(chatAdapter.itemCount - 1)
            } catch (e: Exception) {
                chatAdapter.addMessage(Message("Network error: ${e.message}", isUser = false))
            } finally {
                setLoading(false)
            }
        }
    }

    private fun clearDocuments() {
        setLoading(true)
        lifecycleScope.launch {
            try {
                val response = RetrofitClient.api.resetDocuments()
                if (response.isSuccessful) {
                    isDocumentLoaded = false
                    binding.tvStatus.text = "No documents uploaded"
                    Toast.makeText(this@MainActivity, "Vector store cleared", Toast.LENGTH_SHORT).show()
                }
            } catch (e: Exception) {
                Toast.makeText(this@MainActivity, "Error clearing store: ${e.message}", Toast.LENGTH_SHORT).show()
            } finally {
                setLoading(false)
            }
        }
    }

    private fun setLoading(loading: Boolean) {
        binding.progressBar.visibility = if (loading) View.VISIBLE else View.GONE
        binding.btnUpload.isEnabled = !loading
        binding.btnClear.isEnabled = !loading
        binding.btnSend.isEnabled = !loading
    }

    // ---- Helpers ----

    private fun queryFileName(uri: Uri): String {
        var name = "document.pdf"
        contentResolver.query(uri, null, null, null, null)?.use { cursor ->
            val idx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (idx >= 0 && cursor.moveToFirst()) name = cursor.getString(idx)
        }
        return name
    }

    private fun copyUriToCache(uri: Uri, fileName: String): File {
        val file = File(cacheDir, fileName)
        contentResolver.openInputStream(uri)?.use { input ->
            FileOutputStream(file).use { output ->
                input.copyTo(output)
            }
        }
        return file
    }
}
