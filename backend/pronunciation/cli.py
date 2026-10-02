import argparse
import json
from pathlib import Path
from .engine import GopEngine
from .schemas import Metadata

def main():
    parser=argparse.ArgumentParser(description='Real acoustic pronunciation diagnostics; no scores without calibration')
    parser.add_argument('audio',type=Path)
    parser.add_argument('--text',required=True)
    parser.add_argument('--output',type=Path)
    args=parser.parse_args()
    result=GopEngine().assess(args.audio.read_bytes(),Metadata(requestId='cli',text=args.text))
    data=result.model_dump_json(indent=2)
    if args.output:
        args.output.parent.mkdir(parents=True,exist_ok=True)
        args.output.write_text(data,encoding='utf-8')
    else: print(data)

if __name__=='__main__': main()
