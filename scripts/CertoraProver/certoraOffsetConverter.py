#     The Certora Prover
#     Copyright (C) 2025  Certora Ltd.
#
#     This program is free software: you can redistribute it and/or modify
#     it under the terms of the GNU General Public License as published by
#     the Free Software Foundation, version 3 of the License.
#
#     This program is distributed in the hope that it will be useful,
#     but WITHOUT ANY WARRANTY; without even the implied warranty of
#     MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
#     GNU General Public License for more details.
#
#     You should have received a copy of the GNU General Public License
#     along with this program.  If not, see <https://www.gnu.org/licenses/>.


import bisect
from pathlib import Path

from CertoraProver.certoraBuildDataClasses import SDC
from Shared import certoraUtils as Util


class OffsetConverter:
    """Holds newline positions for a file to enable offset-to-line-column conversion."""

    def __init__(self, file: str):
        """Initialize OffsetConverter by reading newline positions from a file."""
        with Path(file).open('rb') as f:
            content = f.read()
        self.newline_positions = [i for i, byte in enumerate(content) if byte == ord(b'\n')]

    def offset_to_line_column(self, offset: int) -> tuple[int, int]:
        """
        Convert a file offset to line and column number.

        Args:
            offset: Byte offset in the file

        Returns:
            Tuple of (line_number, column_number), both 1-indexed
        """
        line = bisect.bisect_left(self.newline_positions, offset)
        # Calculate column based on previous newline position
        if line == 0:
            column = offset + 1  # 1-indexed, no previous newline
        else:
            column = offset - self.newline_positions[line - 1]  # 1-indexed from newline
        return line, column


def generate_offset_converters(sdc: SDC) -> dict[str, OffsetConverter]:
    original_files = {Util.convert_path_for_solc_import(c.original_file) for c in sdc.contracts}
    return {file: OffsetConverter(file) for file in original_files}
