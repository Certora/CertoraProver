#      The Certora Prover
#      Copyright (C) 2025  Certora Ltd.
#
#      This program is free software: you can redistribute it and/or modify
#      it under the terms of the GNU General Public License as published by
#      the Free Software Foundation, version 3 of the License.
#
#      This program is distributed in the hope that it will be useful,
#      but WITHOUT ANY WARRANTY; without even the implied warranty of
#      MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
#      GNU General Public License for more details.
#
#      You should have received a copy of the GNU General Public License
#      along with this program.  If not, see <https://www.gnu.org/licenses/>.

import argparse
import logging

from langchain_core.language_models.chat_models import BaseChatModel
from langchain_anthropic import ChatAnthropic

from concordance.logger import logger
from concordance.workflows import execute_rewrite_workflow, generate_harness

def setup_argument_parser() -> argparse.ArgumentParser:
    """Configure command line argument parser."""
    parser = argparse.ArgumentParser(description="Certora Concordance Tool for Solidity Function Rewriting")
    parser.add_argument("input_file", help="Input Solidity file containing the function to process")
    parser.add_argument("--harness-model", default="claude-sonnet-4-20250514",
                        help="Model to use for harness generation (default: claude-sonnet-4-20250514)")
    parser.add_argument("--rewrite-model", default="claude-opus-4-20250514",
                        help="Model to use for function rewriting (default: claude-opus-4-20250514)")
    parser.add_argument("--harness-tokens", type=int, default=1024,
                        help="Token budget for harness generation (default: 1024)")
    parser.add_argument("--rewrite-tokens", type=int, default=4096,
                        help="Token budget for function rewriting (default: 4096)")
    parser.add_argument("--thinking-tokens", type=int, default=2048,
                        help="Token budget for thinking in rewriting (default: 2048)")
    parser.add_argument("--debug", action="store_true",
                        help="Enable debug logging output")
    parser.add_argument("--checkpoint-id", type=str, help="Checkpoint id for resuming workflows")
    parser.add_argument("--thread-id", type=str, help="Thread id to use for execution. Randomly generated if not provided")
    parser.add_argument("--db", type=str, help="Path for a database file for persistent executions")
    parser.add_argument("--iteration-limit", type=int, help="The maximum number of iterations allowed during rewrites", default=40)
    return parser


def setup_logging(debug: bool) -> None:
    """Configure logging based on debug flag."""
    if debug:
        import langchain
        langchain.debug = True
        logger.setLevel(logging.DEBUG)
        if not logger.handlers:
            handler = logging.StreamHandler()
            handler.setFormatter(logging.Formatter('%(asctime)s - %(name)s - %(levelname)s - %(message)s'))
            logger.addHandler(handler)


def create_harness_llm(args: argparse.Namespace) -> BaseChatModel:
    """Create and configure the harness generation LLM."""
    llm = ChatAnthropic(
        model_name=args.harness_model,
        max_tokens_to_sample=args.harness_tokens,
        temperature=0,
        timeout=None,
        max_retries=2,
        stop=None
    )
    return llm


def create_rewrite_llm(args: argparse.Namespace) -> BaseChatModel:
    """Create and configure the rewrite LLM."""
    llm = ChatAnthropic(
        model_name=args.rewrite_model,
        max_tokens_to_sample=args.rewrite_tokens,
        temperature=1,
        timeout=None,
        max_retries=2,
        stop=None,
        thinking={"type": "enabled", "budget_tokens": args.thinking_tokens}
    )
    return llm

def main() -> int:
    """Main entry point for the concordance tool."""
    parser = setup_argument_parser()
    args = parser.parse_args()

    setup_logging(args.debug)

    # Create configured LLMs
    harness_llm = create_harness_llm(args)
    rewrite_llm = create_rewrite_llm(args)

    print("Generating wrapper harness contract...")

    with open(args.input_file, 'r') as f:
        original_func = f.read()

    # Generate harness
    harness = ""
    if args.checkpoint_id is None:
        harness = generate_harness(harness_llm, original_func)

    print("Harness contract complete, computing rewrite")

    # Execute rewrite workflow
    return execute_rewrite_workflow(rewrite_llm, harness, args, original_func)
