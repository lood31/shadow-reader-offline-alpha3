from typing import Literal
from pydantic import BaseModel, Field

class Metadata(BaseModel):
    requestId: str = Field(min_length=1, max_length=100)
    text: str = Field(min_length=1, max_length=1000)
    language: Literal['en-US'] = 'en-US'

class Phone(BaseModel):
    phonemeIndex: int
    expected: str
    ipa: str
    score: float | None = None
    confidence: float = Field(ge=0, le=1)
    status: Literal['GREEN', 'YELLOW', 'RED', 'UNKNOWN'] = 'UNKNOWN'
    startMs: int | None = None
    endMs: int | None = None
    gopRaw: float | None = None
    supportFrames: int | None = None
    logMeanPosterior: float | None = None
    nonBlankMass: float | None = None
    targetCompetitorLogRatio: float | None = None
    reasonCode: str | None = None
    detected: str | None = None
    likelyPhoneme: str | None = None
    feedbackRuleId: str | None = None

class Word(BaseModel):
    wordIndex: int
    text: str
    sourceStart: int
    sourceEnd: int
    startMs: int | None = None
    endMs: int | None = None
    score: float | None = None
    confidence: float = Field(default=0, ge=0, le=1)
    status: Literal['GREEN', 'YELLOW', 'RED', 'UNKNOWN'] = 'UNKNOWN'
    coverage: float = 0
    phonemes: list[Phone] = Field(default_factory=list)

class Assessment(BaseModel):
    schemaVersion: int = 1
    requestId: str
    text: str
    audioSha256: str
    language: str = 'en-US'
    assessmentStatus: Literal['ASSESSED', 'PARTIAL', 'UNEVALUATED'] = 'UNEVALUATED'
    calibrationStatus: str = 'UNCALIBRATED'
    calibrationVersion: str | None = None
    confidenceKind: str = 'HEURISTIC'
    timestampKind: str = 'ESTIMATED_CTC'
    engineId: str = 'remote-gop'
    modelVersion: str
    scoringVersion: str = 'ctc_align_mean_log_v1'
    configVersion: str
    accuracyScore: float | None = None
    overallScore: float | None = None
    completenessScore: float | None = None
    fluencyScore: float | None = None
    prosodyScore: float | None = None
    coverage: float = 0
    words: list[Word] = Field(default_factory=list)
    reasonCode: str | None = None
    warnings: list[str] = Field(default_factory=list)
    timingsMs: dict[str, float] = Field(default_factory=dict)

class AssessmentError(Exception):
    def __init__(self, code: str, message: str, status: int = 422):
        super().__init__(message)
        self.code, self.status = code, status
