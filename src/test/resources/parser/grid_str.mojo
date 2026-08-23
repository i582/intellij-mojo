
def grid_str(rows: Int, cols: Int, grid: List[List[Int]]) -> String:
    # Create an empty String
    var str = String()

    # Iterate through rows 0 through rows-1
    for row in range(rows):
        # Iterate through columns 0 through cols-1
        for col in range(cols):
            if grid[row][col] == 1:
                str += "*"  # If cell is populated, append an asterisk
            else:
                str += " "  # If cell is not populated, append a space
        if row != rows-1:
            str += "\n"     # Add a newline between rows, but not at the end
    return str
