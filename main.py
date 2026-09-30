from fastapi import FastAPI, UploadFile, File, HTTPException, Form
from fastapi.middleware.cors import CORSMiddleware
from pydantic import BaseModel
from typing import List, Optional
import shutil
import os
import uvicorn

from utils import process_pdfs, retrieve_context, generate_answer, reset_store, get_indexed_documents

app = FastAPI(
    title="Document Question Answering System (RAG API)",
    description="Backend API for RAG-based document question answering from PDFs",
    version="1.0.0"
)

app.add_middleware(
    CORSMiddleware,
    allow_origins=["*"],
    allow_credentials=True,
    allow_methods=["*"],
    allow_headers=["*"],
)


class QueryRequest(BaseModel):
    question: str


class QueryResponse(BaseModel):
    answer: str
    sources: Optional[List[str]] = None


@app.get("/")
def health():
    return {
        "status": "ok",
        "service": "Doc QA RAG System API",
        "indexed_docs": get_indexed_documents()
    }


@app.get("/documents")
def list_documents():
    """Lists all documents currently indexed in the vector database."""
    return get_indexed_documents()


@app.post("/upload")
async def upload_documents(
    files: List[UploadFile] = File(...),
    reset: bool = Form(False)
):
    """
    Uploads one or more PDF documents to index into the vector database.
    Set `reset=true` if you want to clear existing documents before indexing.
    """
    if reset:
        reset_store()

    temp_paths = []
    for file in files:
        if not file.filename.lower().endswith(".pdf"):
            raise HTTPException(status_code=400, detail=f"File '{file.filename}' is not a PDF.")

        temp_path = f"temp_{file.filename}"
        with open(temp_path, "wb") as buffer:
            shutil.copyfileobj(file.file, buffer)
        temp_paths.append(temp_path)

    try:
        result = process_pdfs(temp_paths)
    except Exception as e:
        raise HTTPException(status_code=500, detail=f"Processing failed: {e}")

    return {
        "message": f"Successfully processed {len(temp_paths)} file(s).",
        "result": result
    }


@app.post("/reset")
def reset_documents():
    """Clears all documents from the vector database."""
    reset_store()
    return {"message": "Vector store reset successfully."}


@app.post("/ask", response_model=QueryResponse)
async def ask_question(request: QueryRequest):
    """Retrieves relevant document context and generates an answer for the question."""
    if not request.question.strip():
        raise HTTPException(status_code=400, detail="Question cannot be empty.")

    chunks = retrieve_context(request.question)
    answer = generate_answer(request.question, chunks)

    sources = list(set([f"{c['source']} (p. {c['page']})" for c in chunks])) if chunks else []
    return QueryResponse(answer=answer, sources=sources)


if __name__ == "__main__":
    uvicorn.run(app, host="0.0.0.0", port=8000)
