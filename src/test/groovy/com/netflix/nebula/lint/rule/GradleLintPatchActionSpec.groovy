    @Rule TemporaryFolder temp
        generator.patch([new GradleLintDelete(f, 1..1)]) == expect
        generator.patch([new GradleLintDelete(f, 1..1)]) == expect
        def fix2 = new GradleLintDelete(f, 3..3)