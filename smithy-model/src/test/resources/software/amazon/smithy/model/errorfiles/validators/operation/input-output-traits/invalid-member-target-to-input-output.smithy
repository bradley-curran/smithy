$version: "1.0"
namespace smithy.example

@input
structure GetFooInput {}

@output
structure GetFooOutput {}

structure BadStructure {
    i: GetFooInput,
    o: GetFooOutput
}
