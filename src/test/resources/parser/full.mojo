from `package.with.dots`.subpackage.`module.with.dots` import foo
import math as m, collections

comptime AscendingMidpoint[lo: Int, hi: Int]: Int where lo < hi = (lo + hi) / 2
comptime greeting = "hello"

@value
struct Pair[T: Copyable](Copyable):
    var first: T
    var second: T

    fn __init__(out self, first: T, second: T):
        self.first = first^
        self.second = second^

trait Printable:
    def print(self):
        pass

__extension Pair:
    def swapped(self) -> Pair[T]:
        return Pair(self.second, self.first)

def transform[T: Copyable](values: List[T], func: def(T) -> T) raises:
    var result = [func(value) for value in values if value]
    return result

async def consume(stream):
    async for item in stream:
        match item:
            case Pair(first, second) if first:
                print(first, second)
            case [head, *tail]:
                print(head, tail)
            case _:
                pass

def guarded(value):
    try:
        with open("file.txt") as file:
            return {"value": value, **file.read()}
    except Error as error:
        raise error from value
    finally:
        print("done")

comptime Attr = __mlir_attr.`dialect.attribute`
comptime Type = __mlir_type.`i32`

def mlir(value):
    return __mlir_op.`dialect.operation`(value)

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
