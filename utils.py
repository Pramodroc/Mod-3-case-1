import os
from typing import List, Dict, Any, Optional
from langchain_community.document_loaders import PyPDFLoader
from langchain_text_splitters import RecursiveCharacterTextSplitter
from langchain_community.embeddings import HuggingFaceEmbeddings
from langchain_community.vectorstores import FAISS
from langchain_core.documents import Document

# Global in-memory vector store & tracked documents
_vector_store: Optional[FAISS] = None
_embeddings = None
_indexed_documents: Dict[str, int] = {}  # filename -> chunk_count


def get_embeddings():
    """Lazy-load the embedding model (downloads on first use)."""
    global _embeddings
    if _embeddings is None:
        _embeddings = HuggingFaceEmbeddings(
            model_name="sentence-transformers/all-MiniLM-L6-v2",
            model_kwargs={"device": "cpu"},
        )
    return _embeddings


def process_pdfs(file_paths: List[str]) -> Dict[str, Any]:
    """
    Loads one or more PDFs, splits them into chunks, and appends them to the vector DB.
    Returns details about the newly indexed documents and total chunks.
    """
    global _vector_store, _indexed_documents

    all_chunks: List[Document] = []
    splitter = RecursiveCharacterTextSplitter(
        chunk_size=800,
        chunk_overlap=150,
        separators=["\n\n", "\n", ".", " ", ""],
    )

    new_doc_summary = {}

    for file_path in file_paths:
        if not os.path.exists(file_path):
            continue

        filename = os.path.basename(file_path)
        # Clean temp prefix if present
        if filename.startswith("temp_"):
            clean_name = filename[5:]
        else:
            clean_name = filename

        try:
            loader = PyPDFLoader(file_path)
            documents = loader.load()

            if not documents:
                continue

            # Ensure metadata source uses clean_name
            for doc in documents:
                doc.metadata["source_name"] = clean_name
                doc.metadata["page"] = doc.metadata.get("page", 0) + 1  # 1-based page index

            chunks = splitter.split_documents(documents)
            all_chunks.extend(chunks)

            doc_chunk_count = len(chunks)
            _indexed_documents[clean_name] = _indexed_documents.get(clean_name, 0) + doc_chunk_count
            new_doc_summary[clean_name] = doc_chunk_count

        except Exception as e:
            print(f"Error processing {filename}: {e}")
        finally:
            try:
                os.remove(file_path)
            except OSError:
                pass

    if all_chunks:
        embeddings = get_embeddings()
        if _vector_store is None:
            _vector_store = FAISS.from_documents(all_chunks, embeddings)
        else:
            _vector_store.add_documents(all_chunks)

    return {
        "indexed_new_documents": new_doc_summary,
        "total_documents": len(_indexed_documents),
        "total_chunks": sum(_indexed_documents.values()),
        "document_list": list(_indexed_documents.keys()),
    }


def retrieve_context(question: str, k: int = 4) -> List[Dict[str, Any]]:
    """Retrieves top-k relevant document chunks along with source metadata."""
    if _vector_store is None:
        return []

    docs = _vector_store.similarity_search(question, k=k)
    results = []
    for doc in docs:
        source = doc.metadata.get("source_name", doc.metadata.get("source", "Document"))
        page = doc.metadata.get("page", 1)
        results.append({
            "source": source,
            "page": page,
            "content": doc.page_content.strip()
        })
    return results


def generate_answer(question: str, retrieved_chunks: List[Dict[str, Any]]) -> str:
    """
    Generates an answer based on retrieved context.
    Attempts external LLM call if environment variables are configured,
    otherwise uses a structured context-grounded response.
    """
    if not retrieved_chunks:
        return "I couldn't find any relevant information in the uploaded documents. Please upload documents first or rephrase your question."

    # Format context with source citations
    context_blocks = []
    sources_used = set()
    for idx, chunk in enumerate(retrieved_chunks, 1):
        source_label = f"[{chunk['source']}, Page {chunk['page']}]"
        sources_used.add(f"{chunk['source']} (Page {chunk['page']})")
        context_blocks.append(f"Excerpt {idx} {source_label}:\n{chunk['content']}")

    full_context = "\n\n---\n\n".join(context_blocks)

    # 1. Check for OpenAI API Key
    openai_key = os.getenv("OPENAI_API_KEY")
    if openai_key:
        try:
            from langchain_openai import ChatOpenAI
            llm = ChatOpenAI(model="gpt-4o-mini", temperature=0, api_key=openai_key)
            prompt = (
                f"You are a helpful assistant for document question answering.\n"
                f"Answer the question based strictly on the provided document excerpts below. "
                f"Cite the source documents where applicable.\n\n"
                f"Context:\n{full_context}\n\n"
                f"Question: {question}\n\nAnswer:"
            )
            return llm.predict(prompt)
        except Exception as e:
            print(f"OpenAI LLM error: {e}")

    # 2. Check for Ollama Local LLM
    ollama_host = os.getenv("OLLAMA_HOST")
    if ollama_host:
        try:
            from langchain_community.llms import Ollama
            llm = Ollama(base_url=ollama_host, model=os.getenv("OLLAMA_MODEL", "llama3"))
            prompt = (
                f"Context:\n{full_context}\n\n"
                f"Question: {question}\n\n"
                f"Answer the question using the context above:"
            )
            return llm.invoke(prompt)
        except Exception as e:
            print(f"Ollama LLM error: {e}")

    # 3. Intelligent Standalone RAG Synthesizer (No API key required)
    sources_str = ", ".join(sorted(sources_used))
    excerpts_preview = "\n\n".join([f"• {b}" for b in context_blocks[:3]])

    return (
        f"Based on the indexed document(s) [{sources_str}]:\n\n"
        f"{excerpts_preview}\n\n"
        f"📌 Relevant Sources: {sources_str}"
    )


def get_indexed_documents() -> Dict[str, Any]:
    """Returns status and list of loaded documents."""
    return {
        "total_documents": len(_indexed_documents),
        "total_chunks": sum(_indexed_documents.values()),
        "documents": _indexed_documents,
    }


def reset_store():
    """Clears the vector store and document registry."""
    global _vector_store, _indexed_documents
    _vector_store = None
    _indexed_documents = {}
